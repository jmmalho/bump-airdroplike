# Atualizar o repositório GitHub existente para BUMP v0.5

Este ZIP patch conserva a mesma estrutura do projeto que já tens no GitHub.

1. Abre no GitHub a pasta `BUMP_v0.4_S24Ultra_TabS7FE_SoundMotion/`.
2. Substitui **exatamente** os caminhos seguintes pelos incluídos no ZIP:

```
BUMP_v0.4_S24Ultra_TabS7FE_SoundMotion/
  app/build.gradle
  app/src/main/java/pt/johnny/bump/PulseView.java
  app/src/main/java/pt/johnny/bump/PreviewActivity.java
  app/src/main/java/pt/johnny/bump/ShareActivity.java
  app/src/main/java/pt/johnny/bump/ReceiverService.java
  tests/check_assets.py
  CHANGELOG.md
  README.md
```

3. Faz commit (mensagem sugerida: `BUMP v0.5 — Wave Handoff`).
4. Abre **Actions → Build BUMP APK**. Mantém o workflow `.github/workflows/build-apk.yml` que já compilava.
5. Descarrega a APK de **Artifacts** e instala-a nos dois Galaxy. Conserva o mesmo `applicationId`.
6. No BUMP, abre a pré-visualização e toca **Ver sequência AirDrop Wave** antes de testar com um PDF.

Se ocorrer uma falha no GitHub Actions, descarrega o log completo para encontrarmos o erro inicial.
