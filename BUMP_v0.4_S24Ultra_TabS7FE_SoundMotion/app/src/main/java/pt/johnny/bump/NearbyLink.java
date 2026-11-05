package pt.johnny.bump;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Nearby transport. Bluetooth and Wi-Fi are handled by Google Play services.
 * Trust model: first connection confirms Nearby's authentication digits on BOTH devices;
 * a random 256-bit shared pairing key is exchanged over that authenticated encrypted channel.
 * Later sessions still require mutual HMAC authentication before sending any file.
 */
final class NearbyLink {
    interface Listener {
        void onStatus(String message);
        void onPairRequest(String endpointId, String name, String digits);
        void onReady(String peerName);
        void onBeacon(String token);
        void onIncoming(String name);
        void onIncomingSaved(String name, Uri uri);
        void onSending(String name);
        void onSendingFinished();
        void onProgress(int percentage);
        void onDisconnected();
        void onError(String message);
    }
    static final String SERVICE_ID = "pt.johnny.bump.nearby.v3";
    private final Context context;
    private final ConnectionsClient connections;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final boolean sender;
    private final String localId;
    private final Map<Long, Payload> incoming = new HashMap<>();
    private final Map<Long, Payload> completed = new HashMap<>();
    private final Map<Long, Meta> metadata = new HashMap<>();
    private final Queue<HistoryStore.LocalFile> outgoing = new ArrayDeque<>();
    private Payload activeOutgoing;
    private HistoryStore.LocalFile activeFile;
    private String endpoint, peerId = "", peerName = "", sharedKey = "", localNonce, remoteNonce, pendingSecret;
    private boolean confirmedPair, connected, authenticated, connecting, closed, transferring;
    private String beaconToken;
    private long lastAttempt;

    NearbyLink(Context context, boolean sender, Listener listener) {
        this.context = context.getApplicationContext();
        this.sender = sender;
        this.listener = listener;
        this.connections = Nearby.getConnectionsClient(context);
        localId = Prefs.localId(context);
    }
    void setBeaconToken(String token) { beaconToken = token; }
    private void status(String s) { handler.post(() -> listener.onStatus(s)); }
    private void error(String s) { handler.post(() -> listener.onError(s)); }
    void startReceive() {
        if (closed) return;
        connections.startAdvertising(Prefs.name(context), SERVICE_ID, lifecycle,
                new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnSuccessListener(v -> status("Disponível para receber"))
                .addOnFailureListener(e -> error("Receção indisponível: " + e.getMessage()));
    }
    void startSendDiscovery() {
        if (closed) return;
        status("À procura do teu tablet…");
        connections.startDiscovery(SERVICE_ID, discovery,
                new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnFailureListener(e -> error("Não consegui encontrar o tablet: " + e.getMessage()));
    }
    void approvePairing(String endpointId, boolean accept) {
        if (closed || endpoint == null || !endpoint.equals(endpointId)) return;
        if (!accept) {
            connections.rejectConnection(endpointId);
            return;
        }
        confirmedPair = true;
        connections.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener(e -> error("Falha ao emparelhar: " + e.getMessage()));
    }
    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            handler.post(() -> {
                if (closed || connecting || connected) return;
                String id = Prefs.peerId(info.getEndpointName());
                if (id.isEmpty() || id.equals(localId)) return;
                // The first pairing is still confirmed by the user via Nearby's digits.
                if (android.os.SystemClock.elapsedRealtime() - lastAttempt < 1800) return;
                lastAttempt = android.os.SystemClock.elapsedRealtime();
                connecting = true;
                endpoint = endpointId;
                peerId = id;
                peerName = Prefs.peerLabel(info.getEndpointName());
                sharedKey = Prefs.key(context, peerId);
                status("A ligar a " + peerName + "…");
                connections.requestConnection(Prefs.name(context), endpointId, lifecycle)
                        .addOnFailureListener(e -> {
                            connecting = false;
                            endpoint = null;
                            error("Ligação recusada ou indisponível: " + e.getMessage());
                        });
            });
        }
        @Override public void onEndpointLost(String endpointId) { }
    };
    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            handler.post(() -> {
                if (closed) { connections.rejectConnection(endpointId); return; }
                String id = Prefs.peerId(info.getEndpointName());
                if (id.isEmpty() || id.equals(localId) || (endpoint != null && !endpoint.equals(endpointId))) {
                    connections.rejectConnection(endpointId);
                    return;
                }
                endpoint = endpointId;
                peerId = id;
                peerName = Prefs.peerLabel(info.getEndpointName());
                sharedKey = Prefs.key(context, id);
                confirmedPair = false;
                if (!sharedKey.isEmpty()) {
                    connections.acceptConnection(endpointId, payloadCallback)
                            .addOnFailureListener(e -> error("Falha de ligação: " + e.getMessage()));
                } else {
                    listener.onPairRequest(endpointId, peerName, info.getAuthenticationDigits());
                }
            });
        }
        @Override public void onConnectionResult(String endpointId, ConnectionResolution result) {
            handler.post(() -> {
                connecting = false;
                if (closed) return;
                if (!result.getStatus().isSuccess()) {
                    connected = false;
                    endpoint = null;
                    error("A ligação falhou. Verifica Bluetooth e Wi-Fi.");
                    return;
                }
                connected = true;
                connections.stopDiscovery();
                if (sender && sharedKey.isEmpty() && confirmedPair) {
                    pendingSecret = Prefs.randomHex(32);
                    sendControl("PAIR|" + pendingSecret);
                } else if (!sharedKey.isEmpty()) {
                    sendHello();
                }
                status("A verificar o dispositivo…");
            });
        }
        @Override public void onDisconnected(String endpointId) {
            handler.post(() -> {
                if (closed || !endpointId.equals(endpoint)) return;
                endpoint = null;
                connected = authenticated = transferring = false;
                incoming.clear(); completed.clear(); metadata.clear();
                listener.onDisconnected();
            });
        }
    };
    private void sendControl(String value) {
        if (endpoint == null || closed) return;
        connections.sendPayload(endpoint, Payload.fromBytes(value.getBytes(StandardCharsets.UTF_8)))
                .addOnFailureListener(e -> error("Ligação interrompida: " + e.getMessage()));
    }
    private void sendHello() {
        if (sharedKey.isEmpty() || !connected) return;
        if (localNonce == null) localNonce = Prefs.randomHex(16);
        sendControl("HELLO|" + localId + "|" + localNonce);
    }
    private String proof(String authorId, String authorNonce, String otherNonce) {
        try {
            byte[] key = new byte[sharedKey.length() / 2];
            for (int i = 0; i < key.length; i++) key[i] = (byte) Integer.parseInt(sharedKey.substring(i * 2, i * 2 + 2), 16);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signature = mac.doFinal(("BUMP3|" + authorId + "|" + authorNonce + "|" + otherNonce).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : signature) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { return ""; }
    }
    private void handleControl(String message) {
        if (!connected || endpoint == null) return;
        String[] parts = message.split("\\|", 5);
        switch (parts[0]) {
            case "PAIR":
                if (parts.length != 2 || sender || !confirmedPair || !sharedKey.isEmpty() || !parts[1].matches("[a-f0-9]{64}")) break;
                Prefs.trust(context, peerId, parts[1]);
                sharedKey = parts[1];
                sendControl("PAIR_ACK");
                sendHello();
                break;
            case "PAIR_ACK":
                if (sender && confirmedPair && pendingSecret != null && sharedKey.isEmpty()) {
                    Prefs.trust(context, peerId, pendingSecret);
                    sharedKey = pendingSecret;
                    pendingSecret = null;
                    sendHello();
                }
                break;
            case "HELLO":
                if (parts.length != 3 || !parts[1].equals(peerId) || !parts[2].matches("[0-9a-f]{32}") || sharedKey.isEmpty()) break;
                remoteNonce = parts[2];
                if (localNonce == null) sendHello();
                sendControl("AUTH|" + proof(localId, localNonce, remoteNonce));
                break;
            case "AUTH":
                if (parts.length != 2 || remoteNonce == null || localNonce == null || sharedKey.isEmpty()) break;
                String expected = proof(peerId, remoteNonce, localNonce);
                if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
                    error("Identidade não confirmada. Faz novo emparelhamento nas definições.");
                    connections.disconnectFromEndpoint(endpoint);
                    break;
                }
                if (!authenticated) {
                    authenticated = true;
                    listener.onReady(peerName);
                    if (!sender && beaconToken != null) sendControl("BEACON|" + beaconToken);
                }
                break;
            case "BEACON":
                if (sender && authenticated && parts.length == 2 && parts[1].matches("[a-f0-9]{8}")) listener.onBeacon(parts[1]);
                break;
            case "ARM":
                if (!sender && authenticated && parts.length >= 2) listener.onIncoming("A receber…");
                break;
            case "META":
                if (sender || !authenticated || parts.length != 4) break;
                try {
                    long payloadId = Long.parseLong(parts[1]);
                    String name = new String(Base64.decode(parts[2], Base64.NO_WRAP), StandardCharsets.UTF_8);
                    String mime = new String(Base64.decode(parts[3], Base64.NO_WRAP), StandardCharsets.UTF_8);
                    metadata.put(payloadId, new Meta(HistoryStore.clean(name), mime));
                    finalizeFile(payloadId);
                } catch (Exception ignored) { }
                break;
        }
    }
    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override public void onPayloadReceived(String endpointId, Payload payload) {
            handler.post(() -> {
                if (closed || endpoint == null || !endpoint.equals(endpointId)) return;
                if (payload.getType() == Payload.Type.BYTES) {
                    byte[] bytes = payload.asBytes();
                    if (bytes != null && bytes.length <= 32768) handleControl(new String(bytes, StandardCharsets.UTF_8));
                } else if (payload.getType() == Payload.Type.FILE) {
                    if (!authenticated || sender) { connections.cancelPayload(payload.getId()); return; }
                    incoming.put(payload.getId(), payload);
                }
            });
        }
        @Override public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            handler.post(() -> {
                if (closed) return;
                long id = update.getPayloadId();
                if (update.getStatus() == PayloadTransferUpdate.Status.IN_PROGRESS) {
                    if (update.getTotalBytes() > 0 && (incoming.containsKey(id) || (activeOutgoing != null && activeOutgoing.getId() == id))) {
                        listener.onProgress((int) Math.min(100, 100.0 * update.getBytesTransferred() / update.getTotalBytes()));
                    }
                } else if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                    Payload file = incoming.remove(id);
                    if (file != null) {
                        completed.put(id, file);
                        finalizeFile(id);
                    } else if (activeOutgoing != null && activeOutgoing.getId() == id) {
                        if (activeFile != null) HistoryStore.add(context, "sent", activeFile.name, activeFile.mime, activeFile.uri, peerName);
                        activeOutgoing.close(); activeOutgoing = null; activeFile = null;
                        sendNext();
                    }
                } else if (update.getStatus() == PayloadTransferUpdate.Status.FAILURE || update.getStatus() == PayloadTransferUpdate.Status.CANCELED) {
                    if (incoming.remove(id) != null || (activeOutgoing != null && activeOutgoing.getId() == id)) {
                        error("A transferência foi interrompida.");
                        if (activeOutgoing != null) { activeOutgoing.close(); activeOutgoing = null; }
                    }
                }
            });
        }
    };
    private static final class Meta {
        final String name, mime;
        Meta(String name, String mime) { this.name = name; this.mime = mime; }
    }
    private void finalizeFile(long id) {
        Payload file = completed.get(id);
        Meta meta = metadata.get(id);
        if (file == null || meta == null) return;
        completed.remove(id); metadata.remove(id);
        Uri source = file.asFile() == null ? null : file.asFile().asUri();
        if (source == null) { error("Ficheiro recebido sem acesso ao conteúdo."); return; }
        files.execute(() -> {
            try {
                Uri output = HistoryStore.saveReceived(context, source, meta.name, meta.mime);
                HistoryStore.add(context, "received", meta.name, meta.mime, output, peerName);
                handler.post(() -> listener.onIncomingSaved(meta.name, output));
            } catch (Exception e) { error("Não foi possível guardar o ficheiro: " + e.getMessage()); }
            finally {
                try { context.getContentResolver().delete(source, null, null); } catch (Exception ignored) { }
                file.close();
            }
        });
    }
    boolean isAuthenticated() { return authenticated; }
    void sendFiles(java.util.List<HistoryStore.LocalFile> list) {
        if (!sender || !authenticated || transferring || list.isEmpty()) return;
        transferring = true;
        outgoing.addAll(list);
        sendControl("ARM|" + list.size());
        sendNext();
    }
    private void sendNext() {
        activeFile = outgoing.poll();
        if (activeFile == null) {
            transferring = false;
            listener.onSendingFinished();
            return;
        }
        try {
            activeOutgoing = Payload.fromFile(activeFile.file);
            String name = Base64.encodeToString(activeFile.name.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String mime = Base64.encodeToString(activeFile.mime.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            sendControl("META|" + activeOutgoing.getId() + "|" + name + "|" + mime);
            listener.onSending(activeFile.name);
            connections.sendPayload(endpoint, activeOutgoing).addOnFailureListener(e -> error("Não consegui enviar o ficheiro: " + e.getMessage()));
        } catch (FileNotFoundException e) {
            error("O ficheiro deixou de estar disponível.");
            sendNext();
        }
    }
    void close() {
        closed = true;
        connections.stopDiscovery();
        connections.stopAdvertising();
        connections.stopAllEndpoints();
        if (activeOutgoing != null) activeOutgoing.close();
        files.shutdown();
    }
}
