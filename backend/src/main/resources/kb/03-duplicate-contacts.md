# Avoiding duplicate contacts
Contacts are matched on the key field you choose in the mapping (email is the default). If the target has no unique constraint on that field, FlowSync cannot upsert and will insert a new row each run. Set the match key in the connector mapping and enable "Upsert on key".
