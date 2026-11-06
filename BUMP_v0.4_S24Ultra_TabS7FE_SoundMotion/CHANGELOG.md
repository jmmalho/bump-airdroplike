# v0.6.0 — Surface Handoff (rebrand)

- `PulseView.java` redesenhado: sem painel central dominante; material compacto,
  origem luminosa superior, onda radial de superfície, token de ficheiro e conclusão.
- `LiquidRefraction.java`: shader AGSL que desloca os píxeis próprios do BUMP no
  Android 13+; desativação segura em aparelhos incompatíveis/poupança de energia.
- Nova identidade de cor/ícone, tipografia nativa, material branco pérola.
- Envio/receção usam uma linguagem visual topo→topo (referência AirDrop proximity).
- A animação só termina após a conclusão real do envio/gravação; mínimos ajustados
  para 1480 ms, sem atrasar os bytes reais.
- Preview de demonstração atualizado; controlos recolhem durante a coreografia.
- Mantidos: Smart Open (PDF, foto, vídeo, APK com consentimento), BLE/Nearby,
  histórico, segurança de emparelhamento e os seis WAV originais.

## Limites deliberados
- O Android não permite refratar píxeis de outras apps numa overlay comum.
- O gesto BLE não equivale a NFC/UWB nem garante toque de centímetros.
- Compilação Gradle e análise visual em hardware pendentes.
