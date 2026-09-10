# Prompt à coller dans le repo `bagbuddy-front`

Le backend BagBuddy (`bagbuddy-back`) vient de changer. Trois changements touchent le contrat
GraphQL et demandent une adaptation ici. Vérifie chaque point dans le code avant de modifier :
certains ne s'appliquent peut-être pas si le front n'utilise pas l'opération concernée.

## 1. `transactionsByUser` a été supprimé (BREAKING si utilisé)

La query `transactionsByUser(userId: String!)` n'existe plus sur `/transactions/graphql`.
Elle était un doublon exact de `myTransactions` : le resolver forçait `userId == sub` du token,
donc l'argument ne portait aucune information.

- Cherche `transactionsByUser` dans le front.
- Remplace chaque appel par `myTransactions`, qui ne prend **aucun argument** et retourne
  exactement les mêmes lignes (achats et ventes de l'appelant, plus récentes d'abord).
- Supprime le passage du `userId`/`sub` devenu inutile à l'appel.

`transactionsBySeller(sellerId:)` et `transactionsByBuyer(buyerId:)` sont **conservées** :
elles restent des sous-ensembles filtrés utiles. Ne les touche pas.

## 2. `remainingWeight` n'est plus acceptable dans `TripInput` (BREAKING si envoyé)

Le champ `remainingWeight` a été retiré de l'input type `TripInput` sur `/trips/graphql`.
La capacité restante est désormais **décidée par le serveur** : c'est de l'inventaire, un
vendeur ne doit pas pouvoir se re-créditer du poids déjà vendu.

Important : ce n'est **pas** un champ ignoré en silence. GraphQL rejette maintenant toute
requête qui le contient, avec `errors[0].extensions.classification = "ValidationError"`.
Si le front l'envoie encore, **la création et la mise à jour d'annonce échouent complètement**.

- Cherche `remainingWeight` dans les payloads de `createTrip` et `updateTrip` et retire-le.
- Nouveau comportement serveur, à refléter dans l'UI :
  - à la création, `remainingWeight = totalWeightAvailable` ;
  - à la mise à jour, `remainingWeight` suit la **variation** de `totalWeightAvailable`
    (agrandir l'annonce de 5 kg ajoute 5 kg de disponible ; réduire en retire autant),
    borné entre 0 et le nouveau total.
- `remainingWeight` reste **lisible** en sortie sur le type `Trip` : les affichages ne changent pas.
- Conséquence UI : tout champ de formulaire laissant saisir la capacité restante doit
  disparaître. Le vendeur ne règle que `totalWeightAvailable`.

## 3. Pagination optionnelle sur les listes (NON breaking)

Quatre queries acceptent maintenant `limit: Int` et `offset: Int`, tous deux **optionnels** :

- `/trips/graphql` : `trips`, `activeTrips`, `inactiveTrips`
- `/reviews/graphql` : `reviews`

Les requêtes actuelles sans arguments continuent de fonctionner — **mais** la réponse est
désormais plafonnée à **200 éléments** côté serveur, là où elle était illimitée. Si une vue
peut légitimement dépasser 200 lignes (le browse d'annonces, la liste d'avis), elle affiche
aujourd'hui une liste tronquée en silence.

- Repère les vues qui listent des trips ou des reviews sans filtre.
- Ajoute une pagination (scroll infini ou pages) en passant `limit`/`offset`.
- `limit` est ramené dans `[1, 200]` côté serveur : demander 1000 renvoie 200, sans erreur.
- Il n'y a pas de champ `totalCount` : pour savoir s'il reste des pages, demande `limit + 1`
  éléments et affiche-en `limit`, ou passe à la page suivante tant que la réponse est pleine.

## 4. Nouveau code d'erreur possible sur `/users/graphql` (à gérer)

`register`, `updateIdentity` et `changePassword` passent maintenant par un coupe-circuit vers
Keycloak. Quand Keycloak ne répond pas, l'erreur retournée est :

```json
{ "extensions": { "classification": "INTERNAL_ERROR", "code": "service_unavailable" } }
```

C'est le même contrat que les autres services exposaient déjà. Si le front a un traitement
générique de `extensions.code === "service_unavailable"` (« service momentanément indisponible,
réessayez »), il couvre déjà ce cas — vérifie simplement que le chemin d'inscription en
bénéficie, puisque c'est la seule opération anonyme du système.

## Ce qui n'a PAS changé

Inutile de toucher à ça : l'authentification (direct access grant Keycloak + bearer token vers
le gateway), les chemins `/trips/graphql`, `/transactions/graphql`, `/reviews/graphql`,
`/stripe/graphql`, `/users/graphql`, le vocabulaire des statuts de transaction, la forme des
types `Trip`, `Transaction`, `Review` en **sortie**, et `createPaymentIntent(transactionId:)`.

## Vérification attendue

Après modification, confirme que :
1. `grep -r "transactionsByUser"` ne retourne plus rien ;
2. `grep -r "remainingWeight"` n'apparaît plus dans un payload de mutation (seulement en lecture) ;
3. la création et la modification d'une annonce fonctionnent de bout en bout ;
4. les listes longues ne sont plus tronquées silencieusement à 200.
