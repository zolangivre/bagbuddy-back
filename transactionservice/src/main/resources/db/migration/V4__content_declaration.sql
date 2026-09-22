-- Declaration du contenu a la reservation : ce que l'acheteur confie au voyageur, et son
-- engagement sur la liste des objets interdits. Le vendeur la lit avant d'accepter.
-- Les reservations anterieures n'en ont pas : description nulle, engagement faux.
ALTER TABLE transaction_record ADD COLUMN content_description VARCHAR(500);
ALTER TABLE transaction_record ADD COLUMN prohibited_items_accepted BOOLEAN NOT NULL DEFAULT FALSE;
