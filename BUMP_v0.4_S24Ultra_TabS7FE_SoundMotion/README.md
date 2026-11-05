# BUMP v0.4.3 — Liquid Glass / Blue Edition

Atualização visual para **Galaxy S24 Ultra ↔ Galaxy Tab S7 FE**, baseada no BUMP v0.4.2. Não necessita de NFC nem de acessórios.

## O que mudou

- **Vidro translúcido azul-claro**, visível e legível, inspirado na sensação das janelas de AirDrop e Quick Share, sem copiar a interface da Apple ou da Samsung.
- Efeito nativo de **desfoque da janela que está por trás**, quando Android 12+ / One UI o permite. Se a poupança de energia ou o próprio sistema o desativarem, a interface mantém o material translúcido com cores e contornos próprios.
- Reflexos delicados, rebordo luminoso, sombra suave e halo azul contido dentro do painel.
- Mesmos gestos e sons do BUMP v0.4.2; o PDF sai por cima e entra por baixo, com curvas ease-in/ease-out.
- Botões de cancelar e envio manual agora com aparência de vidro, pré-visualização harmonizada e ecrã principal em azul-claro.
- O efeito mais pesado só aparece enquanto se está a partilhar/receber; **não acrescenta varrimentos Bluetooth nem processamento de blur constante** ao serviço em segundo plano.

## Segurança e limites

- A deteção de proximidade continua a basear-se em sinal Bluetooth, portanto não mede centímetros exatos. O botão «Enviar sem gesto» mantém-se disponível.
- Para animar a receção por cima de outras apps, concede a permissão «Mostrar sobre outras aplicações». O Android pode limitar o blur do overlay; nesse caso o painel continua translúcido por si só.
- Se o ecrã estiver desligado, a receção pode ocorrer mas não é garantido acordar o tablet automaticamente.
- A receção permanente exige o serviço em primeiro plano e um indicador do sistema operativo.
- Não foi testado num Galaxy físico nem foi gerada uma APK neste ambiente. O ZIP é o **código-fonte**, para compilar no teu GitHub Actions.

## Atualizar o repositório

Abre `INSTALL_PATCH.md` para os oito ficheiros a substituir/adicionar. Mantém o workflow de GitHub Actions que já conseguiste corrigir.

## Verificações executadas

- 21 verificações do algoritmo de proximidade (`tests/run.sh`).
- Integridade dos seis sons WAV e respetivos eventos.
- XML do tema e manifesto válidos.
- Sintaxe de 15 ficheiros Java analisada com o parser do Java 17.

Estas verificações **não substituem** uma compilação Gradle/Android real.
