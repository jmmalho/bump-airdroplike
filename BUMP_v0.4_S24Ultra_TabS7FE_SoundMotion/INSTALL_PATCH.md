# Instalar o BUMP v0.5.1 no GitHub

Parte da v0.5 Wave Handoff. Mantém o workflow do GitHub Actions atual.

1. Abre a pasta do projeto Android, normalmente `BUMP_v0.4_S24Ultra_TabS7FE_SoundMotion`.
2. Copia/substitui os ficheiros da pasta do patch para os mesmos caminhos no repositório.
3. Ficheiros NOVOS: `app/src/main/java/pt/johnny/bump/OpenFileActivity.java`, `LiquidRefraction.java`.
4. Ficheiros MODIFICADOS: `app/src/main/AndroidManifest.xml`, `app/build.gradle`, `MainActivity.java`, `ReceiverService.java`, `PulseView.java`.
5. Faz commit. GitHub Actions deverá executar `Build BUMP APK`.
6. Instala a APK atualizada em AMBOS os aparelhos. Se for assinada com a mesma chave da APK atual, os dados e emparelhamentos mantêm-se. Atenção: as APKs debug geradas em runners temporários podem usar chaves de assinatura diferentes; nesse caso a atualização por cima pode falhar e uma reinstalação apaga os dados locais. Para atualizações futuras sem perdas, configura uma chave de assinatura persistente no CI.

Teste: partilha um PDF pequeno, espera até aparecer `Recebido`, toca `Abrir PDF`. Repete com uma fotografia e uma APK de origem confiável. Também deves poder abri-los no histórico BUMP.

Se a compilação falhar, envia o primeiro erro relevante do log. Esta versão ainda não foi compilada com o SDK Android num runner externo.
