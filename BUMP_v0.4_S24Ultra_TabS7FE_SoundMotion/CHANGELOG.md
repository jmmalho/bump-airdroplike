# BUMP — histórico de versões

## v0.4 beta · Som e movimento

- **Seis sons originais** em WAV gerados localmente: `ready`, `send`, `incoming`, `sent`, `received` e `error`. Os cinco principais têm timbres próprios; o erro usa um aviso discreto.
- **Pronto a partilhar**: o documento surge gradualmente e a aura azul constrói-se com easing. Toca o som `bump_ready` depois de o ficheiro estar preparado para partilha.
- **A enviar**: documento sai pelo topo, trilho e efeito ascendente `bump_send`.
- **Enviado**: confirmação visual distinta e som `bump_sent`, ativados depois de o transporte confirmar o último ficheiro.
- **A receber**: animação de baixo para cima e som de chegada `bump_incoming`, em qualquer dispositivo com a receção BUMP ativada.
- **Recebido**: efeito de brilho/onda, som `bump_received`, só depois de guardar o ficheiro em Downloads/BUMP.
- **Erro**: estado visual e aviso sonoro curto, sem fingir transferência concluída.
- Adicionadas opções independentes **Efeitos sonoros** e **Vibração discreta** nas definições, ativadas por padrão. Respeita o modo silencioso/vibração do Android.
- Adicionada a área **Experimentar as animações e os sons** na app, sem necessidade de outro dispositivo ou de ficheiro real.
- Os efeitos são locais e só tocam ao acontecer um evento; sem gravação de microfone nem reprodução em loop no serviço.
- Adicionado `tools/generate_sounds.py` para regenerar os WAV originais com NumPy.
- Não foram adicionadas novas permissões Android.

**Estado:** código-fonte em beta, ainda não compilado como APK nem ensaiado fisicamente nos dois aparelhos.

## v0.3 beta

- Proximidade BLE experimental sem NFC, emparelhamento/autenticação, Nearby Connections, histórico, sobreposição transparente, partilha via Sharesheet e teste Java local.
