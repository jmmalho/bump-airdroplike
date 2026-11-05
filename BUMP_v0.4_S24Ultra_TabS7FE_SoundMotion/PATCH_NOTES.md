# BUMP v0.4.1 — Correção Android resource linking

Erro confirmado no GitHub Actions #6: `style attribute android:attr/windowDimAmount not found`.

Alteração única em `app/src/main/res/values/styles.xml`:

- Antes: `<item name="android:windowDimAmount">0</item>` (atributo de tema inválido)
- Agora: `<item name="android:backgroundDimEnabled">false</item>` (não escurecer o fundo da janela transparente)

Repetir o build existente: o workflow atual está a funcionar até `:app:processDebugResources`.

O ZIP contém todo o projeto, mas **não** é preciso voltar a importar tudo para o GitHub.
A forma mais rápida é editar apenas `BUMP_v0.4_S24Ultra_TabS7FE_SoundMotion/app/src/main/res/values/styles.xml` no repositório já criado.

Validação local: XML analisado, testes existentes passaram. APK ainda não compilada nesta sessão.
