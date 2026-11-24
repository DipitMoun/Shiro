# Shiro push gateway

Cloudflare Worker qui relaie les notifications push Matrix vers Firebase Cloud Messaging.

```
serveur Matrix -> POST /_matrix/push/v1/notify -> ce worker -> FCM -> téléphone
```

Les pushers sont enregistrés en `event_id_only` : le worker ne voit que des identifiants de
salon et d'événement, jamais le contenu des messages. Shiro récupère le message lui-même.

## Déploiement

```sh
cd push-gateway
npx wrangler login
npx wrangler secret put FCM_SERVICE_ACCOUNT < chemin/vers/compte-de-service.json
npx wrangler deploy
```

La clé du compte de service Firebase ne doit jamais être commitée.

## Vérifier

`GET https://shiro-push.<sous-domaine>.workers.dev/` répond `shiro push gateway`.
