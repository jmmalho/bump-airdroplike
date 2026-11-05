# BUMP v0.5.1 — Smart Open + Elastic Wave

- Receção: ação contextual para abrir o documento diretamente após a animação, sem entrar na app.
- PDF → leitor de PDFs; imagens → galeria/visualizador; vídeo e áudio → apps compatíveis.
- APK → pedido de confirmação e instalador do Android, nunca execução automática.
- Histórico: mesmo mecanismo de abertura segura, para ficheiros enviados ou recebidos.
- MIME: fallback pelo nome/extensão quando Nearby informa application/octet-stream.
- Android 13+: efeito de refração AGSL aplicado exclusivamente aos píxeis do BUMP durante a onda de envio/receção.
- Versões anteriores: animação Canvas mantida como fallback.
- Sem novas bibliotecas de terceiros; mantém BLE, Nearby, Liquid Glass e sons.

## Segurança e limites
- Não abre automaticamente ficheiros quando a app está no background.
- Android exige autorização do utilizador para instalar APKs de origem desconhecida.
- Em dispositivos sem visualizador compatível, a app informa o utilizador.
- A deformação ótica não altera o ecrã de outras aplicações.
