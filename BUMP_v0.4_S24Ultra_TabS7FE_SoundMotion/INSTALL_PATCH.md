# Instalação do patch BUMP v0.6 — Surface Handoff

Este patch é incremental e foi criado sobre **v0.5.1 Smart Open Wave**.

## Importante

O teu repositório atual usa uma pasta principal chamada
`BUMP_v0.4_S24Ultra_TabS7FE_SoundMotion`. O ZIP patch contém os ficheiros
**relativos ao interior dessa pasta**. Não cries uma pasta nova com o nome v0.6:
abre a pasta existente no GitHub e substitui os ficheiros com os mesmos caminhos.

## Substituir os seguintes ficheiros

- `app/src/main/java/pt/johnny/bump/PulseView.java`
- `app/src/main/java/pt/johnny/bump/LiquidRefraction.java`
- `app/src/main/java/pt/johnny/bump/GlassStyle.java`
- `app/src/main/java/pt/johnny/bump/MainActivity.java`
- `app/src/main/java/pt/johnny/bump/ShareActivity.java`
- `app/src/main/java/pt/johnny/bump/ReceiverService.java`
- `app/src/main/java/pt/johnny/bump/PreviewActivity.java`
- `app/src/main/res/drawable/ic_bump.xml`
- `app/build.gradle`
- `tests/check_assets.py` (testes atualizados)

Podes também substituir `README.md` e `CHANGELOG.md`.

## Depois

1. Faz commit. Mantém o workflow `.github/workflows/build-apk.yml` atual.
2. Em Actions, verifica o job **Build APK**. Se der erro, envia o ZIP dos logs.
3. Descarrega `BUMP-debug-APK` e instala a APK nos dois aparelhos.
4. Abre BUMP → Som e movimento → Experimentar as animações e os sons → Ver Surface Handoff.
5. Experimenta partilhar um PDF pequeno e um ficheiro maior.

## Dados e atualização

Não desinstales a APK anterior sem backup: as APKs debug podem ter assinaturas
diferentes entre builds e uma reinstalação limpa pode apagar o histórico e
as chaves locais de emparelhamento.

Esta versão ainda não foi compilada/testada num Galaxy real.
