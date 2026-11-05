# BUMP v0.5 — WAVE EDITION

Projeto Android para S24 Ultra e Galaxy Tab S7 FE (sem NFC nem etiquetas).

**O que mudou na v0.5:** animação *Wave Handoff* original: ao preparar a partilha,
ondas discretas respiram à volta do documento; no envio, a onda atravessa o topo
do ecrã e o documento transforma-se num rasto de luz ascendente; no tablet, uma
onda nasce na parte inferior e o documento emerge do fundo, com amortecimento
no final. O sucesso só é apresentado após a transferência real concluir.

Em **BUMP → Som e movimento → Experimentar as animações e os sons**, usa o novo
botão **Ver sequência AirDrop Wave** para ver o movimento completo sem ficheiros.
Os botões individuais para os cinco estados continuam disponíveis.

A linguagem visual é inspirada no AirDrop e Quick Share, mas o desenho e os sons
são originais. O movimento é criado por Canvas + ValueAnimator, sem bibliotecas
externas, blur por frame ou processos de rede adicionais.

O gesto BLE, a transferência Nearby Connections, o histórico e a receção em
segundo plano mantêm a implementação da v0.4.3. Bluetooth RSSI continua a não
medir distâncias em centímetros. O 'handoff' sincroniza o significado visual
nos dois ecrãs; não representa a posição espacial exata entre os aparelhos.

**Instalação:** substitui os ficheiros indicados em `INSTALL_PATCH.md`; não
alteres a pasta `.github/workflows` já funcional no teu repositório. Faz commit,
executa a build e instala a APK gerada nos dois Galaxy.

**Validação:** testes locais para lógica BLE, áudio, XML e ligação de eventos;
compilação real da APK e fluidez nos dispositivos ainda por validar.

---

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


## v0.5.1 — Abrir ficheiros recebidos

Após a confirmação de receção, aparece um botão pequeno `Abrir PDF` / `Ver fotografia` / `Instalar APK` junto ao efeito Wave, quando a sobreposição estiver autorizada e o ecrã estiver ligado. Também podes tocar em qualquer entrada do histórico BUMP para abrir a app correspondente. APKs só passam ao instalador após consentimento explícito e eventual autorização nas definições Android.

Em Android 13+ a animação Wave pode refratar ligeiramente os elementos desenhados pelo BUMP usando AGSL. Não é uma deformação do conteúdo de outras aplicações.

**Nota sobre instalação:** versões debug do GitHub Actions podem ter assinaturas diferentes entre execuções. Se Android recusar atualizar a APK, não desinstales sem antes aceitares que o histórico/emparelhamentos locais possam desaparecer.
