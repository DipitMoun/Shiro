# Phase 0 — Critères d'acceptation

La Phase 0 est terminée quand les décisions ci-dessous sont suffisamment précises
pour qu'un développeur puisse implémenter le prototype sans reposer les questions
de modèle, d'audience ou d'interface.

## 1. Modèle de données

- [ ] Un `workId` canonique est défini pour AniList, MAL, AniDB, TVDB, Telegram
      et les œuvres locales.
- [ ] La résolution passe par une table d'alias et reste fail-closed sans
      identifiant commun ; aucun rapprochement par titre seul.
- [ ] Un `episodeKey` stable est défini pour les épisodes normaux, les spéciaux,
      les films et les numéros décimaux (`S01E003`, `S00E001`, `MAIN`).
- [ ] Un `sourceKey` permet de distinguer deux versions du même épisode.
- [ ] `positionMs`, `source.durationMs`, `beforeMs` et `afterMs` sont documentés.
- [ ] La politique de dérive entre sources est écrite : exact si même source ou
      durée proche, approximatif sinon, sans recalage automatique par ratio.
- [ ] Le recalage manuel local (`source_offset`) est documenté.
- [ ] Le schéma `org.shiro.moment` v1 est fourni avec un exemple JSON complet.
- [ ] Le fallback texte lisible par les autres clients Matrix est défini.
- [ ] L'édition et la redaction d'un message ancré sont couvertes.

## 2. Salles et fils

- [ ] La salle est nommée et scopée par auteur + œuvre.
- [ ] Le fil est scopé par auteur + épisode et créé dynamiquement au premier geste.
- [ ] Le root event du fil et les métadonnées sont définis.
- [ ] Les amis de 1er degré sont invités ; les amis d'amis ne le sont jamais.
- [ ] Le flux d'adhésion (invitation, join, réponse) est décrit ; pas de knock en v1.
- [ ] Un ami sans l'œuvre reçoit une proposition d'ajout.
- [ ] L'absence d'expiration et la conservation de l'historique sont explicites.

## 3. Multi-auteurs et audiences

- [ ] Kevin, Allan et David peuvent chacun créer leur fil sur le même moment.
- [ ] Répondre dans le fil de Kevin ne crée pas automatiquement un fil Allan.
- [ ] `Publier à mes amis` crée/alimente le fil de l'utilisateur.
- [ ] Les fils de plusieurs auteurs sont regroupés par moment sans mélange de
      membres.
- [ ] Le regroupement local (même œuvre + épisode + écart ≤ 15 s) est défini ;
      aucune clé de moment n'est fédérée.
- [ ] Aucun message d'une audience ne fuit vers une autre.

## 4. Timecodes et player

- [ ] Un message peut contenir un ou plusieurs timecodes.
- [ ] Un timecode capturé est stocké en millisecondes, pas seulement en texte.
- [ ] Un clic sur un timecode lance la boucle 15/15.
- [ ] Dans le player normal, un clic sur un timecode peut seek directement.
- [ ] La pause de la boucle retourne à l'affiche.
- [ ] `Reprendre l'épisode` repart de l'ancre.
- [ ] Les bords d'épisode sont clampés.
- [ ] Le comportement en cas de durée inconnue est défini.

## 5. Épinglage et réactions

- [ ] L'épinglage utilise un événement de fil chiffré, pas `m.room.pinned_events`
      qui est room-level et non chiffré.
- [ ] Maximum 3 pins par fil ; seul l'auteur peut épingler ; une quatrième
      épingle est refusée sans remplacer silencieusement une ancienne.
- [ ] Les réactions de message utilisent `m.reaction`.
- [ ] Une réaction au moment est une réaction sur le message ancré correspondant.

## 6. Notifications et confidentialité

- [ ] Le transport instantané est choisi : UnifiedPush + ntfy obligatoire ;
      WorkManager uniquement en rattrapage ; FCM rejeté.
- [ ] L'onboarding détecte le distributeur et propose son installation s'il
      manque ; l'absence de distributeur est affichée explicitement.
- [ ] L'enregistrement du pusher Matrix (`app_id`, `pushkey`, `kind:http`,
      `format:event_id_only`) est documenté.
- [ ] Les conséquences du choix sont documentées : délai, batterie, réseau.
- [ ] Le chiffrement de bout en bout est décidé : E2EE dès la création.
- [ ] Les state events non chiffrés ne contiennent pas de contenu sensible.
- [ ] Les notifications ne divulguent pas le contenu chiffré.
- [ ] Le homeserver cible ou la politique de choix est documenté.

## 7. Interface

- [ ] Wireframes paysage : pause sans fil, pause avec fil, boucle, composer,
      clavier, multi-auteurs, états vides.
- [ ] Largeur du rail, du panneau de fil et marges définies.
- [ ] Le fil reste lisible sans être directement sur une image en mouvement.
- [ ] Les actions `Répondre ici` et `Publier à mes amis` sont impossibles à
      confondre.
- [ ] Le mode paysage exclusif et les zones sûres sont traités.
- [ ] L'accessibilité (contraste, TalkBack, mouvement réduit) est couverte.

## 8. Scénarios de test

### Scénario A — Kevin seul

1. Kevin ouvre Frieren S1E1 à 01:40 et met en pause.
2. Il publie un message ancré à 01:40.
3. La salle `Kevin · Frieren` et le fil S1E1 sont créés.
4. Ses amis directs reçoivent l'invitation.
5. Un ami sans Frieren voit la proposition d'ajout.

### Scénario B — Allan répond

1. Allan ouvre le fil de Kevin.
2. Il choisit `Répondre ici`.
3. Son message reste dans l'audience de Kevin.
4. Aucun ami d'Allan ne voit ce message.

### Scénario C — Allan devient auteur

1. Allan choisit `Publier à mes amis`.
2. Un fil Allan/Frieren/S1E1 est créé ou alimenté.
3. Les amis directs d'Allan reçoivent le message.
4. Kevin ne le voit que s'il est aussi ami direct d'Allan.
5. Les deux fils sont regroupés sous le même moment dans l'UI.

### Scénario D — David au même moment

1. David publie lui aussi à 01:40.
2. Trois fils existent : Kevin, Allan, David.
3. L'UI affiche trois conversations groupées.
4. Aucune audience n'est fusionnée.

### Scénario E — Boucle et timecode

1. Un message contient `01:40`.
2. Un clic lance la boucle 09:25–09:55 (15/15).
3. Le son est coupé par défaut.
4. Une pause retourne à l'affiche.
5. `Reprendre` repart de 01:40.

### Scénario F — Source différente

1. Allan a une version de l'épisode avec 20 s de décalage.
2. Le timecode est marqué approximatif (`≈`).
3. Aucun seek automatique silencieux n'est effectué ; Allan peut confirmer ou
   appliquer un offset local.
4. L'offset manuel décale le seek sans modifier le message d'origine.
5. Aucun crash si la durée est inconnue.

### Scénario G — Message épinglé

1. Kevin épingle trois messages ancrés.
2. Les trois apparaissent dans la bande `Moments` avec une étoile.
3. Allan ne peut pas les épingler (`403`).
4. Une quatrième épingle est refusée avec « Maximum 3 moments clés ».
5. L'épinglage survit à la fermeture de l'app ; la redaction d'une cible la retire.

### Scénario I — Notification instantanée

1. Allan a installé le distributeur UnifiedPush (ntfy) et enregistré le pusher.
2. Kevin publie un moment alors que Shiro est fermé.
3. Allan reçoit une notification générique en quelques secondes.
4. Un tap ouvre la salle, synchronise et déchiffre le message.
5. Si le distributeur est retiré, Shiro affiche « notifications instantanées
   indisponibles » et ne prétend pas que WorkManager est instantané.
6. Si le homeserver refuse le pusher, le diagnostic est visible.

### Scénario J — Non-régression Telegram

1. La recherche, les canaux et Avatar Seven Havens fonctionnent comme avant.
2. La couche sociale n'ajoute aucun appel réseau Telegram.
3. Les overrides et la base utilisateur ne sont pas modifiés par la Phase 0.
