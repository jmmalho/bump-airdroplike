# BUMP v0.4 beta — Encosta. Envia.

**Para o Galaxy S24 Ultra e Galaxy Tab S7 FE.** A mesma app pode enviar **ou receber** em qualquer um dos dois. Não é uma modificação do AirDrop nem do Quick Share.

**Estado real:** projeto Android de desenvolvimento; não foi possível compilar ou testar esta versão como APK no ambiente disponível. A proximidade BLE, o emparelhamento, as permissões, o comportamento em segundo plano e as sobreposições Samsung precisam de testes reais. Os sons incluídos são ficheiros WAV válidos; isso **não** confirma que a app funcione no dispositivo.

## A experiência

1. No emissor: abres um PDF/fotografia → **Partilhar → BUMP**. O ecrã anterior continua visível por trás da camada transparente.
2. O documento surge suavemente entre pequenos círculos azuis. Lê-se **Pronto a partilhar**, com o nome do ficheiro; toca um sinal curto e discreto.
3. Depois de estabelecer a ligação segura, a app tenta reconhecer a aproximação através da intensidade do sinal BLE. Isto é uma **estimativa imperfeita**, não um sensor de toque. **Enviar sem gesto** continua disponível.
4. Ao enviar, o documento sobe até desaparecer no topo do ecrã com um efeito de subida. O emissor mostra **Enviado** com um segundo som **depois** de o último ficheiro terminar no transporte.
5. No destinatário, com ecrã ligado/desbloqueado e a permissão de sobreposição, o ficheiro entra visualmente de **baixo para cima** ao começar a transferência. Há um som próprio de chegada.
6. Só depois de ser guardado em **Downloads/BUMP** aparece **Recebido** com uma onda azul e um acorde diferente. Vale igualmente no **telefone** quando o telefone estiver a receber.

A aplicação principal serve para ver enviados/recebidos, abrir ficheiros, ativar receção, alterar a proximidade, gerir emparelhamentos e controlar o som. A partilha diária usa a partilha normal do Android.

## Sons e animações

| Evento | Animação | Ficheiro de áudio |
| --- | --- | --- |
| Pronto a partilhar | Documento faz fade/slide e a aura expande | `bump_ready.wav` |
| A enviar | Documento sai pelo topo com trilho | `bump_send.wav` |
| A receber | Documento entra desde a parte inferior | `bump_incoming.wav` |
| Enviado | Onda expansiva e visto | `bump_sent.wav` |
| Recebido | Documento com visto e brilho | `bump_received.wav` |
| Erro | Estado textual discreto | `bump_error.wav` |

Todos os sons foram sintetizados para o projeto; não são sons da Apple. Não são sons ultrassónicos para deteção de proximidade. São efeitos curtos **não contínuos**, de aproximadamente 0,3–0,74 segundos, reproduzidos apenas em eventos. Inclui vibração háptica discreta, que pode ser desligada separadamente. Quando o Android estiver em modo silencioso ou vibração, o BUMP não reproduz som; a vibração do BUMP tem uma opção própria. Não é feita nenhuma gravação.

Na app, em **Som e movimento → Experimentar as animações e os sons**, podes testar os cinco efeitos **sem transferir ficheiros**, até os dois dispositivos estarem emparelhados.

Os WAV estão em `app/src/main/res/raw`. Para os regenerar, executa `python tools/generate_sounds.py` (requer NumPy). O ZIP já traz os sons prontos; **não precisas** de instalar Python no Android.

## Configuração e uso

1. Compila o código para uma APK debug (ver abaixo) e instala **a mesma APK nos dois Galaxy**.
2. Em ambos, abre BUMP, concede as permissões de dispositivos próximos e, para ver o efeito enquanto usas outras apps, permite **Apresentar sobre outras aplicações**.
3. No dispositivo que recebe, liga **Ativar receção**. Podes ativar nos dois para partilhar nos dois sentidos. O Bluetooth deve estar ligado.
4. Na primeira transferência, mantém a app BUMP aberta no destinatário, pois é preciso confirmar o **mesmo código de segurança nos dois aparelhos**. Depois disso, fica memorizado o emparelhamento.
5. Para usar: Partilhar → BUMP → aproxima os dispositivos. Se o BLE não reconhecer com fiabilidade, toca em **Enviar sem gesto**.

## Criar APK gratuitamente no GitHub

1. Cria um repositório **privado** no GitHub.
2. Coloca o conteúdo **desta pasta**, incluindo `.github`, na raiz do repositório.
3. Abre **Actions → Build BUMP APK → Run workflow**. Se não aparecer, verifica se as Actions estão ativadas.
4. Se a compilação for bem-sucedida, descarrega o artefacto `BUMP-debug-APK` e instala-o nos dois dispositivos. Se falhar, usa a **primeira mensagem de erro** do Gradle para corrigir o projeto; não assumas que a compilação está garantida.

Utiliza Java 17, AGP 8.8.2, Gradle 8.10.2, compile/target SDK 35, min SDK 26 e Play Services Nearby 19.5.1.

## Limitações técnicas e privacidade

- **Não há NFC**, etiqueta, acessório ou Internet obrigatórios. O Nearby Connections escolhe a conectividade local Bluetooth/Wi-Fi, consoante as condições.
- **BLE/RSSI não mede centímetros com precisão.** Aproximar não é um gesto equivalente ao AirDrop dos iPhones; poderá falhar ou detetar erradamente outros movimentos. Só se podem enviar os ficheiros explicitamente escolhidos, para um destinatário emparelhado e autenticado. A primeira ligação requer confirmação manual.
- O **Android exige uma presença visível de serviço em primeiro plano**, por exemplo na área de notificações/tarefas do sistema. O BUMP não gera notificações extra por ficheiro, nem tenta esconder avisos obrigatórios.
- A camada por cima de outras apps **pode não aparecer com o ecrã bloqueado/desligado**. Não há promessa de acordar automaticamente o tablet com o ecrã preto.
- Em alguns aparelhos/versões, as restrições de bateria/segundo plano podem interromper o serviço. A reposição é **best effort**, não garantida; testa antes de depender do serviço sempre ligado.
- A reprodução de um som de chegada pode acontecer mesmo se a interface visual não for permitida, desde que o Android deixe o serviço executar. Os controlos de som e DND do sistema têm prioridade.
- O serviço mantém o rádio necessário à receção, portanto a autonomia exata exige medição real. Os efeitos visuais e sonoros são acionados pontualmente e não mantêm o CPU acordado entre transferências.
- O segredo de emparelhamento fica em preferências privadas do Android, sem proteção adicional por Keystore neste protótipo. Não é um produto auditado para transferir informação confidencial.
- **O ficheiro é guardado em Downloads/BUMP**, e não deve existir apenas a animação. O som de sucesso do destinatário é emitido após confirmar a gravação.

## Testes

`bash tests/run.sh` executa as verificações Java da lógica de proximidade e a validação básica dos recursos áudio/eventos. Estes testes **não substituem** `assembleDebug` nem os testes físicos.

Ensaios a realizar nos dois Galaxy: primeira ligação e emparelhamento, enviar/receber em ambos os sentidos, estado silencioso/vibração, efeitos ligados/desligados, ecrã ligado sobre outras apps, ecrã desligado, Wi-Fi indisponível, Bluetooth desligado/reativado, ficheiros grandes e consumo de bateria em repouso.
