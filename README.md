# BUMP 1.0

Partilha por toque entre dispositivos Android. Escolhes um ficheiro, carregas em **Partilhar › BUMP** e
encostas o topo do telemóvel ao outro aparelho. Os dois sentem o toque ao mesmo tempo e o ficheiro passa,
cifrado, sem internet.

Testado em conceito para **Galaxy S24 Ultra → Galaxy Tab S7 FE**. Funciona entre quaisquer dois Android 8+
com Google Play Services.

## Como funciona o "encostar"

O Tab S7 FE não tem NFC nem UWB, por isso não consegue sentir um toque por rádio. Mas os dois aparelhos
têm acelerómetro, e um toque físico produz um impacto curto e brusco **nos dois ao mesmo tempo**.

1. Ao abrir a folha de partilha, o telemóvel liga-se ao tablet já emparelhado (Nearby Connections) e
   autenticam-se mutuamente (HMAC com segredo guardado no Android Keystore).
2. Os dois sincronizam o relógio por troca de pings (estilo NTP, fica a amostra com menor latência).
3. O telemóvel "arma" o envio. O tablet passa a ouvir o acelerómetro a 100 Hz (só enquanto armado).
4. Cada impacto detetado é marcado com o tempo. O tablet envia os seus ao telemóvel.
5. Se um impacto do telemóvel e um do tablet coincidirem dentro de ~110 ms (+ metade da latência medida),
   é um toque. O sinal Bluetooth LE (RSSI) serve de confirmação de proximidade, nunca de gatilho sozinho.
6. Ambos começam a mesma animação no mesmo instante e os bytes seguem por Wi-Fi direto.

Movimentos lentos da mão, levantar o telemóvel ou pousá-lo não disparam: o detetor exige um impacto
íngreme (variação brusca entre amostras) e a correlação temporal com o outro aparelho.
Se o toque falhar, tocar no avatar do dispositivo ou em **Enviar sem tocar** envia na mesma.

## A animação (estilo NameDrop)

No instante do toque, os dois ecrãs fazem a mesma coreografia, ao mesmo tempo:

1. **Carga** (1,2 s): um brilho branco com aro iridescente cresce no topo, onde os aparelhos se tocam,
   com uma vibração que sobe de intensidade.
2. **Libertação**: um tic seco, o brilho rebenta e uma onda de refração desce pelo ecrã, dobrando e
   iluminando o fundo. É um shader AGSL (`RuntimeShader`, Android 13+), portado do Metal do
   [NameDrop Lab](https://github.com/Georgvm/iOS-NameDrop-Animation) e do
   [NameDrop de cyrilzakka](https://github.com/cyrilzakka/NameDrop) (MIT).
3. **Cartão**: o ficheiro aparece no meio com o progresso; no tablet, com o botão para abrir.

Antes do toque **nada revela a ligação**: a folha de envio diz sempre "Encosta ao outro dispositivo",
sem nomes nem indicadores, e o tablet não mostra nada. O destino só aparece quando a onda passa.

Os parâmetros (velocidade, amplitude, nitidez, largura, distorção, brilho, tempo de carga) afinam-se em
**Definições › Laboratório da animação** e podem ser guardados para as transferências reais.

## Som e movimento

- **Sons** (`res/raw`, OGG): `bump_send` (libertação), `bump_sent`, `bump_received`, `bump_error`.
  Silêncio inicial removido e picos nivelados a -3 dBFS. O `bump_send` cresce até ao pico aos 300 ms,
  por isso começa 0,3 s antes da libertação para o pico cair exatamente no rebentar da onda.
- **Movimento** (`ui/theme/Motion.kt`): molas definidas como no SwiftUI (`response`, `dampingFraction`)
  e entradas com `cubic-bezier(0.16, 1, 0.3, 1)`. A folha de envio entra com mola, segue o dedo e sai
  com a velocidade do gesto (ou volta com ressalto); os ecrãs deslizam com mola; os botões principais
  encolhem ao toque e voltam com ressalto; o cartão do ficheiro chega com uma mola de 3 % de overshoot.

## Tecnologias

| Área | Antes (v0.6) | Agora (1.0) | Porquê |
|---|---|---|---|
| Linguagem | Java | Kotlin 2.2 | Padrão Android, null-safety, menos código |
| UI | Views criadas à mão | Jetpack Compose + Material 3 | Estado declarativo, animações, tema claro/escuro, ecrãs grandes |
| Gatilho | Só RSSI BLE (não mede distância) | Impactos correlacionados + RSSI como confirmação | O tablet não tem NFC/UWB; o acelerómetro é o único sensor de contacto comum |
| Transporte | Nearby Connections | Nearby Connections (mantido) | Mesmo stack do Quick Share: BLE para descobrir, Wi-Fi direto para os bytes |
| Segredos | SharedPreferences em claro | Android Keystore (AES-GCM) | Chaves de emparelhamento não exportáveis |
| Histórico | JSON dentro de SharedPreferences | Ficheiro JSON com escrita atómica | Sem limites de prefs, sem corrupção a meio |
| Som | MediaPlayer por evento (80–200 ms) | SoundPool pré-carregado | O som cai no mesmo frame do toque |
| Vibração | Padrões on/off | Primitivas hápticas (Android 12+) | Toque "click" nítido no S24 Ultra |
| Estado | Referências estáticas à Activity | StateFlow + ViewModel | Sem fugas de memória, sobrevive a rotação |

## Mudanças obrigatórias do Android em 2026

- **Nearby Connections deixa de ligar Bluetooth/Wi-Fi sozinho** (final de 2026). O BUMP verifica os rádios
  antes de cada sessão e mostra botões para os ligar.
- `targetSdk 36` → edge-to-edge obrigatório, já implementado.

## Construir

O GitHub Actions (`.github/workflows/build-apk.yml`) corre os testes unitários e gera:

- `BUMP-debug-APK`
- `BUMP-release-APK` (minificado, assinado com a chave debug para instalares diretamente)

Localmente: Android Studio 2025.1 (Narwhal) ou mais recente → abrir a pasta → Run.

## Primeiro uso

1. Instala a mesma APK nos dois aparelhos (a v1.0 não fala com a v0.6: protocolo novo).
2. No tablet: abre o BUMP, dá as autorizações e toca em **Ativar receção**.
3. No telemóvel: num PDF ou foto, **Partilhar › BUMP**. O tablet aparece; toca nele.
4. Confirma o mesmo código nos dois ecrãs (só da primeira vez).
5. Encosta o topo do telemóvel ao tablet com um toque leve.

Ajustes em **Definições › Toque**: *Leve* se o tablet estiver numa capa grossa, *Firme* se disparar sozinho.

## Estrutura

```
app/src/main/java/pt/johnny/bump/
  link/      Protocol (mensagens), NearbyLink (transporte + auth), Radios/Permissions
  motion/    BumpLogic (detetor, sincronização, correlação — puro, testado), Sensors (acelerómetro, BLE)
  data/      Stores (definições, identidade, dispositivos, histórico), Files (staging, MediaStore, provider)
  share/     ShareActivity + ShareViewModel (folha de envio)
  receive/   ReceiverService (serviço em primeiro plano, overlay, notificações)
  ui/        Compose: tema, ecrãs, componentes, NameDropView (onda AGSL + brilho + cartão)
app/src/test/  BumpLogicTest (12 testes: impacto, ruído, refractário, relógio, correlação, protocolo, HMAC)
```

## Limites conhecidos

- Precisa de Google Play Services nos dois aparelhos.
- O toque é inferido por impacto, não medido por NFC: um toque muito suave num tablet pousado numa
  superfície mole pode não chegar ao limiar. Usa *Leve* ou o botão.
- A animação sobre outras apps precisa da autorização "mostrar por cima"; sem ela, recebes notificação.
