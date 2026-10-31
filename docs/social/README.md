# Couche sociale Shiro

Cette documentation couvre la couche sociale Matrix de Shiro : moments partagés,
fils par épisode, réactions, commentaires et notifications entre amis de 1er degré.

## Statut

Phase 0 terminée le 2026-10-30 : décisions, schéma, wireframes et critères
d'acceptation. Phase 1 terminée : prototype UI local validé, dette nettoyée,
23 tests JVM PASS et build APK OK. L'intégration Matrix peut démarrer.

## Documents

- [PHASE-0-SPEC.md](PHASE-0-SPEC.md) — décisions verrouillées, identité canonique,
  schéma Matrix, salles/fils, audiences, notifications, permissions.
- [PHASE-0-WIREFRAMES.md](PHASE-0-WIREFRAMES.md) — états, wireframes paysage,
  interactions, accessibilité.
- [PHASE-0-ACCEPTANCE.md](PHASE-0-ACCEPTANCE.md) — critères d'acceptation et
  scénarios de test.
- [PHASE-1.md](PHASE-1.md) — prototype UI local (données factices, sans Matrix).
- [schema/org.shiro.moment.v1.schema.json](schema/org.shiro.moment.v1.schema.json)
  — JSON Schema du payload `org.shiro.moment`.
- [schema/example-message.json](schema/example-message.json) — exemple complet
  d'événement Matrix.

## Périmètre verrouillé

- Un fil par **auteur + œuvre + épisode** (modèle A).
- Les moments sont des **ancres temporelles** dans le fil, pas des fils séparés.
- Salle par auteur + œuvre ; audience de **1er degré uniquement**.
- Tous les amis directs reçoivent l'invitation ; ceux qui n'ont pas l'œuvre sont
  invités à l'ajouter.
- `Répondre ici` reste dans le fil de l'auteur ; `Publier à mes amis` crée ou
  alimente le fil de l'utilisateur.
- Plusieurs auteurs peuvent avoir un fil au même moment ; les fils sont regroupés
  visuellement, sans mélange d'audience.
- Boucle de 30 s autour d'un moment : 15 s avant + 15 s après, son coupé par défaut.
- Timecodes cliquables, jusqu'à 3 moments clés épinglés par fil.
- Pas d'expiration native ; pas de message privé dans le fil auteur.
- Notifications instantanées via **UnifiedPush + ntfy** ; distributeur requis.

## Hors périmètre Phase 0

- Implémentation Kotlin/Compose.
- Intégration Matrix Rust SDK.
- Implémentation du pusher UnifiedPush et de l'onboarding distributeur.
- Partage Telegram.
- Streaming progressif Telegram.
