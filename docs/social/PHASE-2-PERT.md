# Phase 2 — Cœur Matrix : todolist PERT

> Estimation en heures de travail effectif pour un développeur.
> Formule PERT : `Te = (To + 4Tm + Tp) / 6`.
> `To` = optimiste, `Tm` = probable, `Tp` = pessimiste.

## Todolist

| ID | TODO | Dépend de | To | Tm | Tp | Te |
|---|---|---|---:|---:|---:|---:|
| P2-01 | Spike Matrix Rust SDK Android : build, bindings, thread support, E2EE | — | 4 | 6 | 10 | 6,3 h |
| P2-02 | Contrats internes : repository, feature flag, frontière SDK/store | P2-01 | 2 | 3 | 5 | 3,2 h |
| P2-03 | Identité canonique : `workId`, alias, `episodeKey`, résolution fail-closed | P2-02 | 4 | 6 | 9 | 6,2 h |
| P2-04 | Payload `org.shiro.moment` v1 : sérialisation, validation, fallback body, edits/redactions | P2-02 | 3 | 5 | 8 | 5,2 h |
| P2-05 | Session Matrix : homeserver, login, stockage chiffré, clés, onboarding | P2-01, P2-02 | 4 | 7 | 12 | 7,3 h |
| P2-06 | Salles et fils : salle auteur+œuvre, E2EE, root par épisode, invitations 1er degré, join, permissions | P2-03, P2-04, P2-05 | 6 | 10 | 16 | 10,3 h |
| P2-07 | Sync et store local : timeline, pagination, cache, mapping event/thread | P2-06 | 6 | 9 | 14 | 9,3 h |
| P2-08 | Envoi : répondre ici, publier à mes amis, réactions, édition, redaction | P2-07 | 4 | 6 | 10 | 6,3 h |
| P2-09 | Audience : amis directs, invitation de tous, bannière « ajouter l'œuvre », rejoindre | P2-06 | 4 | 6 | 9 | 6,2 h |
| P2-10 | Branchement UI : remplacer les données factices, loading/error/offline, feature flag | P2-07, P2-08, P2-09 | 4 | 6 | 10 | 6,3 h |
| P2-11 | Tests : unitaires, transport factice, intégration, smoke E2EE, non-régression | P2-10 | 5 | 8 | 12 | 8,2 h |
| P2-12 | Durcissement : perf, rate limits, confidentialité, rollback, doc | P2-11 | 3 | 5 | 8 | 5,2 h |

## Résultat PERT

- Charge totale : **≈ 80 h** de travail effectif.
- Chemin critique : **P2-01 → P2-02 → P2-03 → P2-04 → P2-06 → P2-07 → P2-08 → P2-10 → P2-11 → P2-12**.
- Durée du chemin critique : **≈ 66,5 h**, soit environ **8 jours ouvrés** pour un développeur seul.
- Avec 2 agents en parallèle sur les branches non critiques : **6 à 7 jours ouvrés** réalistes, la chaîne SDK → salles → sync → envoi → tests restant séquentielle.
- L'estimation initiale de 3–5 jours était trop optimiste pour un cœur Matrix complet avec E2EE.

## Ordre de démarrage

1. P2-01 seul, pour lever le risque SDK.
2. Ensuite P2-03 et P2-04 en parallèle de P2-05.
3. P2-06 quand identité, payload et session sont stables.
4. P2-07/P2-08/P2-09 en parallèle après P2-06.
5. P2-10, P2-11, P2-12 pour fermer la phase.
