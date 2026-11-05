# Changelog

## v0.4.3 — Liquid Glass / Blue Edition

- Novo `GlassStyle.java` com materiais translúcidos gradientes, highlights e fallback de cor quando o blur do Android está desativado.
- `PulseView.java`: novo painel flutuante, bordas de vidro, reflexos, paleta azul-clara, tipografia escura de alto contraste e movimentos ascendentes conservados.
- `ShareActivity.java`: blur opcional do Android, botões em vidro, margens em dp.
- `ReceiverService.java`: efeito opcional de desfoque durante receção e ajuste de opacidade do overlay para evitar bloqueio de toques em Android 12+.
- `PreviewActivity.java`: mesma linguagem visual para pré-visualização.
- `MainActivity.java`: cartões de vidro, botões e fundo suave no histórico e definições.
- `styles.xml`: dimming controlado em runtime com tema translucente corrigido.
- Versão Android atualizada para `0.4.3-beta` (versionCode 6).

## v0.4.2 — Proximidade melhorada

Mantidos o limiar de proximidade mais tolerante, as leituras consecutivas com histerese, o anúncio BLE temporariamente mais ativo, o envio manual e a lógica de transferência e emparelhamento.
