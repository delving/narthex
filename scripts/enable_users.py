"""
To run this script do the following:

    $ apt-get update; apt-get install python3-pip; pip3 install sparqlwrapper
    $ python enable_users.py <org_id>

This will enable all users in the triple store. You can disable them via the Narthex User management interface.
"""
import sys

from SPARQLWrapper import SPARQLWrapper, JSON


def get_all_actors(org_id):
    query_url = "http://localhost:3030/{}/sparql".format(org_id)
    print(query_url)
    sparql = SPARQLWrapper(query_url)
    # sparql.setCredentials('fuseki_user', '9@?[ZMh26VcF3jKwucy5')
    sparql.setQuery("""

    SELECT ?actor
    WHERE {
       GRAPH <http://schemas.delving.eu/narthex/terms/Actors/graph> {
          ?actor a 	<http://schemas.delving.eu/narthex/terms/Actor>.
       }
    }
    """)
    sparql.setReturnFormat(JSON)
    return [actor['actor']['value'] for actor in sparql.query().convert()['results']['bindings']]


def enable_all_actors(org_id):
    actors = get_all_actors(org_id)
    for actor in actors:
        print(actor)
        query = """WITH <http://schemas.delving.eu/narthex/terms/Actors/graph>
        DELETE {{
           <{actor}> <http://schemas.delving.eu/narthex/terms/actorEnabled> true ;
            <http://schemas.delving.eu/narthex/terms/actorEnabled> false .
        }}
        INSERT {{
           <{actor}> <http://schemas.delving.eu/narthex/terms/actorEnabled> true .
        }}
        WHERE {{
           <{actor}> a <http://schemas.delving.eu/narthex/terms/Actor> .
           OPTIONAL {{ <$actor> <http://schemas.delving.eu/narthex/terms/actorEnabled> ?actorEnabled .}}
        }}""".format(actor=actor)
        sparql = SPARQLWrapper("http://localhost:3030/{}/update".format(org_id))
        # sparql.setCredentials('fuseki_user', '9@?[ZMh26VcF3jKwucy5')
        sparql.setQuery(query)
        sparql.setMethod('POST')
        results = sparql.query().convert()
        print(results)


if __name__ == "__main__":
    org_id = sys.argv[1]
    enable_all_actors(org_id)
