# Instalar BUMP v0.4.3 no teu GitHub

**Não alteres `.github/workflows/build-apk.yml`.** O workflow corrigido que já compila a v0.4.1 deve manter-se. O repositório continua a chamar-se `bump-airdroplike` e o projeto está dentro da pasta `BUMP_v0.4_S24Ultra_TabS7FE_SoundMotion/`.

## Substituir sete ficheiros e acrescentar um

Dentro de `BUMP_v0.4_S24Ultra_TabS7FE_SoundMotion/`:

1. **NOVO** `app/src/main/java/pt/johnny/bump/GlassStyle.java`
2. **SUBSTITUIR** `app/src/main/java/pt/johnny/bump/PulseView.java`
3. **SUBSTITUIR** `app/src/main/java/pt/johnny/bump/ShareActivity.java`
4. **SUBSTITUIR** `app/src/main/java/pt/johnny/bump/ReceiverService.java`
5. **SUBSTITUIR** `app/src/main/java/pt/johnny/bump/PreviewActivity.java`
6. **SUBSTITUIR** `app/src/main/java/pt/johnny/bump/MainActivity.java`
7. **SUBSTITUIR** `app/src/main/res/values/styles.xml`
8. **SUBSTITUIR** `app/build.gradle`

Os ficheiros ficam todos na mesma pasta de código do BUMP que já existe. **Não os coloques na raiz do repositório** e não crias uma segunda pasta de projeto.

Faz commit; o workflow `Build BUMP APK` deve iniciar automaticamente. Depois de um visto verde, abre o artefacto `BUMP-debug-APK`, extrai o `.apk` e instala nos **dois Galaxy**.

Nota: se o Android disser que não é possível atualizar uma aplicação com a mesma identificação, a chave debug pode ter mudado entre compilação anteriores; guarda os teus ficheiros/histórico antes de eventualmente desinstalar a versão antiga.

## Regressão manual a testar

- Partilhar PDF → BUMP → painel de vidro azul legível, em vez de ecrã invisível.
- Pronto a partilhar → áudio READY; aproximar → som SEND e ficheiro a sair por cima.
- Tablet desbloqueado → a receber → ficheiro sobe; confirmação RECEIVED depois de guardado.
- Botão «Enviar sem gesto» continua a funcionar quando a proximidade BLE falha.
- Abrir a aplicação → histórico, definições, sensibilidade e pré-visualização.
- Ecrã em poupança de energia → painel continua visível mesmo sem blur real.

Este pacote foi revisto estaticamente, **não compilado numa APK real** neste ambiente.
