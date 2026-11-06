# BUMP v0.6 — Surface Handoff

Prototipo Android de partilha por proximidade para Galaxy S24 Ultra e Galaxy Tab S7 FE.
Este rebrand foi desenvolvido sobre a base **BUMP v0.5.1**, preservando o envio por
Nearby Connections, a deteção BLE, os seis sons, o histórico e o Smart Open para PDF,
imagens, vídeo e APK (instalação apenas com consentimento explícito).

## Linguagem visual

Inspirada na descrição do efeito de aproximação do AirDrop fornecida pelo utilizador.
Não utiliza código, logótipos, ficheiros sonoros ou elementos de interface da Apple.

- **Tipografia:** `sans-serif`/Roboto Android; título da cápsula 16sp médio, ficheiro
  12sp regular, dicas 11sp. Plataforma nativa para melhor leitura nas duas dimensões.
- **Tokens:** tinta `#1E2B41`, texto secundário `#65748D`, azul ótico `#257BF8`,
  branco porcelana `#FFFFFF`, halo pérola `#DEF4FF`.
- **Estrutura:** origem luminosa na parte superior (9,5% da altura, mínimo 52dp),
  superfície quase transparente e cápsula de vidro opaca na zona inferior (68%).
  Já não há uma grande caixa central cheia de círculos.
- **Movimento:** preparação 500 ms com ease-out cúbico; onda radial + luz volumétrica
  + lançamento do ficheiro em 1480 ms; confirmação 780 ms. A UI guarda tempo para
  o gesto terminar mesmo que o PDF já tenha chegado.
- **Recursos reais:** ícone vectorial original, seis efeitos sonoros originais já
  incluídos no projeto, Canvas nativo + AGSL para refração; sem bibliotecas pesadas.

## Refração: o que é real e o que não é

No Android 13+ (`RuntimeShader` + `RenderEffect`), o shader desloca genuinamente os
píxeis que **o BUMP desenha**. Esta é uma refração, não uma linha decorativa.
Por motivos de isolamento e privacidade do Android, não é possível a uma aplicação
normal deformar os píxeis ao vivo de outra aplicação por trás da sobreposição.
Se o shader não for suportado, a animação Canvas continua a funcionar; em modo
poupança de energia, a distorção é desativada.

A onda começa no topo de ambos os dispositivos, como na referência enviada.
Os efeitos não têm sincronização espacial milimétrica (BLE RSSI não mede centímetros).
São disparados nos eventos de envio e receção. O sistema só mostra sucesso depois
de confirmar a transferência e o armazenamento dos dados.

## Testar

1. Instala a mesma APK nos dois dispositivos. Abre o BUMP no tablet e ativa a receção.
2. No S24 Ultra, seleciona um PDF → Partilhar → BUMP.
3. Aproxima a parte superior dos dois dispositivos, mantendo o ecrã do tablet ligado.
4. Usa «Enviar sem gesto» quando o BLE não detetar proximidade com fiabilidade.
5. No BUMP, abre Som e movimento → Experimentar as animações e os sons →
   **Ver Surface Handoff** para uma demonstração sem transferência.
6. No final, abre o PDF/fotografia/APK a partir da confirmação ou do histórico.

### Atualização do GitHub

O teu repositório mantém a pasta `BUMP_v0.4_S24Ultra_TabS7FE_SoundMotion`.
Usa os caminhos descritos em `INSTALL_PATCH.md` do pacote patch; **não substituas o
workflow GitHub Actions que já funciona no teu repositório**.

### Validação

- `tests/run.sh`: 21 testes de proximidade, integridade dos sons, coerência de
  eventos, XML, estrutura do shader, abertura dos ficheiros e timing visual.
- Compilação de `PulseView.java` e `LiquidRefraction.java` com Java 17 e interfaces
  de teste Android: passou. **Não** é uma build Gradle nem compila AGSL na GPU.
- Não foi possível gerar/instalar a APK nem testar a animação em hardware Galaxy
  neste ambiente. O workflow existente do GitHub é o passo de integração real.

### Referências técnicas

- https://developer.android.com/reference/android/graphics/RuntimeShader
- https://developer.android.com/develop/ui/views/graphics/agsl
- https://source.android.com/docs/core/display/window-blurs
