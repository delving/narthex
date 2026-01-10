import type { TreeNode, SampleRecord, OutputRecord } from './types';
import type { Mapping } from './stores/mappingStore';

// Sample source tree - typical museum collection XML structure
export const sampleSourceTree: TreeNode[] = [
	{
		id: 'root',
		name: 'record',
		path: '/record',
		count: 45230,
		children: [
			{
				id: 'priref',
				name: 'priref',
				path: '/record/priref',
				count: 45230,
				hasValues: true
			},
			{
				id: 'object_number',
				name: 'object_number',
				path: '/record/object_number',
				count: 45230,
				hasValues: true,
				mappedTo: [{ field: 'dc:identifier' }]
			},
			{
				id: 'title',
				name: 'title',
				path: '/record/title',
				count: 44892,
				hasValues: true,
				mappedTo: [
					{ field: 'dc:title', label: 'nl' },
					{ field: 'dc:title', label: 'en' }
				],
				children: [
					{
						id: 'title_lang',
						name: 'lang',
						path: '/record/title/@lang',
						count: 44892,
						isAttribute: true,
						hasValues: true
					}
				]
			},
			{
				id: 'description',
				name: 'description',
				path: '/record/description',
				count: 38421,
				hasValues: true,
				mappedTo: [{ field: 'dc:description' }]
			},
			{
				id: 'maker',
				name: 'maker',
				path: '/record/maker',
				count: 32156,
				children: [
					{
						id: 'maker_name',
						name: 'name',
						path: '/record/maker/name',
						count: 32156,
						hasValues: true,
						mappedTo: [{ field: 'dc:creator' }]
					},
					{
						id: 'maker_role',
						name: 'role',
						path: '/record/maker/role',
						count: 28934,
						hasValues: true
					},
					{
						id: 'maker_qualifier',
						name: 'qualifier',
						path: '/record/maker/qualifier',
						count: 8234,
						hasValues: true
					},
					{
						id: 'maker_birth',
						name: 'birth_date',
						path: '/record/maker/birth_date',
						count: 15632,
						hasValues: true
					},
					{
						id: 'maker_death',
						name: 'death_date',
						path: '/record/maker/death_date',
						count: 12453,
						hasValues: true
					}
				]
			},
			{
				id: 'dating',
				name: 'dating',
				path: '/record/dating',
				count: 41235,
				children: [
					{
						id: 'dating_from',
						name: 'date.early',
						path: '/record/dating/date.early',
						count: 41235,
						hasValues: true,
						mappedTo: [{ field: 'dcterms:created' }]
					},
					{
						id: 'dating_to',
						name: 'date.late',
						path: '/record/dating/date.late',
						count: 38921,
						hasValues: true
					},
					{
						id: 'dating_period',
						name: 'period',
						path: '/record/dating/period',
						count: 29834,
						hasValues: true
					}
				]
			},
			{
				id: 'material',
				name: 'material',
				path: '/record/material',
				count: 39823,
				hasValues: true,
				mappedTo: [{ field: 'dc:format' }]
			},
			{
				id: 'technique',
				name: 'technique',
				path: '/record/technique',
				count: 35621,
				hasValues: true
			},
			{
				id: 'dimensions',
				name: 'dimensions',
				path: '/record/dimensions',
				count: 43256,
				children: [
					{
						id: 'dim_type',
						name: 'type',
						path: '/record/dimensions/type',
						count: 43256,
						hasValues: true
					},
					{
						id: 'dim_value',
						name: 'value',
						path: '/record/dimensions/value',
						count: 43256,
						hasValues: true
					},
					{
						id: 'dim_unit',
						name: 'unit',
						path: '/record/dimensions/unit',
						count: 43256,
						hasValues: true
					}
				]
			},
			{
				id: 'collection',
				name: 'collection',
				path: '/record/collection',
				count: 45230,
				hasValues: true,
				mappedTo: [{ field: 'edm:dataProvider' }]
			},
			{
				id: 'credit_line',
				name: 'credit_line',
				path: '/record/credit_line',
				count: 28934,
				hasValues: true
			},
			{
				id: 'acquisition',
				name: 'acquisition',
				path: '/record/acquisition',
				count: 34521,
				children: [
					{
						id: 'acq_method',
						name: 'method',
						path: '/record/acquisition/method',
						count: 34521,
						hasValues: true
					},
					{
						id: 'acq_date',
						name: 'date',
						path: '/record/acquisition/date',
						count: 31234,
						hasValues: true
					},
					{
						id: 'acq_source',
						name: 'source',
						path: '/record/acquisition/source',
						count: 29823,
						hasValues: true
					}
				]
			},
			{
				id: 'reproduction',
				name: 'reproduction',
				path: '/record/reproduction',
				count: 42156,
				children: [
					{
						id: 'repro_reference',
						name: 'reference',
						path: '/record/reproduction/reference',
						count: 42156,
						hasValues: true,
						mappedTo: [
							{ field: 'edm:isShownBy' },
							{ field: 'edm:WebResource/@rdf:about' }
						]
					},
					{
						id: 'repro_format',
						name: 'format',
						path: '/record/reproduction/format',
						count: 42156,
						hasValues: true
					},
					{
						id: 'repro_type',
						name: 'type',
						path: '/record/reproduction/type',
						count: 38234,
						hasValues: true
					}
				]
			},
			{
				id: 'subject',
				name: 'subject',
				path: '/record/subject',
				count: 28345,
				hasValues: true,
				mappedTo: [{ field: 'dc:subject' }]
			},
			{
				id: 'object_category',
				name: 'object_category',
				path: '/record/object_category',
				count: 45230,
				hasValues: true,
				mappedTo: [{ field: 'edm:type' }]
			},
			{
				id: 'object_name',
				name: 'object_name',
				path: '/record/object_name',
				count: 44892,
				hasValues: true,
				mappedTo: [{ field: 'dc:type' }]
			}
		]
	}
];

// Sample target tree - EDM (Europeana Data Model) schema
export const sampleTargetTree: TreeNode[] = [
	{
		id: 'rdf',
		name: 'rdf:RDF',
		path: '/rdf:RDF',
		documentation: {
			description: 'Root element for RDF/XML serialization of EDM records.',
			notes: 'Contains ProvidedCHO, WebResource, and Aggregation elements.'
		},
		children: [
			{
				id: 'providedCHO',
				name: 'edm:ProvidedCHO',
				path: '/rdf:RDF/edm:ProvidedCHO',
				documentation: {
					description: 'The Cultural Heritage Object - the real-world object or work that is being described.',
					required: true,
					notes: 'This class comprises the Cultural Heritage objects that Europeana collects descriptions about.'
				},
				children: [
					{
						id: 'dc_identifier',
						name: 'dc:identifier',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:identifier',
						mappedFrom: [{ field: 'object_number' }],
						documentation: {
							description: 'An unambiguous reference to the resource within a given context.',
							dataType: 'string',
							required: true,
							repeatable: true,
							examples: ['SK-A-1234', 'NG-2010-1-15', 'BK-1975-81'],
							notes: 'Recommended practice is to identify the resource by means of a string conforming to an identification system. Examples include ISBN, DOI, and museum inventory numbers.'
						}
					},
					{
						id: 'dc_title',
						name: 'dc:title',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:title',
						mappedFrom: [
							{ field: 'title', label: 'nl' },
							{ field: 'title', label: 'en' }
						],
						documentation: {
							description: 'A name given to the resource. Typically, a Title will be a name by which the resource is formally known.',
							dataType: 'langString',
							required: true,
							repeatable: true,
							examples: ['De Nachtwacht', 'The Night Watch', 'Meisje met de parel'],
							notes: 'Use @xml:lang attribute to specify the language. Multiple titles in different languages are encouraged.'
						}
					},
					{
						id: 'dc_description',
						name: 'dc:description',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:description',
						mappedFrom: [{ field: 'description' }],
						documentation: {
							description: 'An account of the resource. Description may include abstract, table of contents, or free-text account.',
							dataType: 'langString',
							repeatable: true,
							examples: ['Schutters van wijk II onder leiding van kapitein Frans Banninck Cocq'],
							notes: 'Can include physical description, provenance information, or historical context.'
						}
					},
					{
						id: 'dc_creator',
						name: 'dc:creator',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:creator',
						mappedFrom: [{ field: 'maker/name' }],
						documentation: {
							description: 'An entity primarily responsible for making the resource.',
							dataType: 'string | URI',
							repeatable: true,
							examples: ['Rembrandt van Rijn', 'http://viaf.org/viaf/64013650'],
							notes: 'Preferably use a URI reference to an authority file (VIAF, ULAN, Wikidata). For literal values, use the form "Lastname, Firstname".'
						}
					},
					{
						id: 'dc_date',
						name: 'dc:date',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:date',
						documentation: {
							description: 'A point or period of time associated with an event in the lifecycle of the resource.',
							dataType: 'string | date',
							repeatable: true,
							examples: ['1642', '17th century', 'ca. 1660-1670'],
							notes: 'Use dcterms:created for creation date, dcterms:issued for publication date. This field is for general date information.'
						}
					},
					{
						id: 'dcterms_created',
						name: 'dcterms:created',
						path: '/rdf:RDF/edm:ProvidedCHO/dcterms:created',
						mappedFrom: [{ field: 'dating/date.early' }],
						documentation: {
							description: 'Date of creation of the resource.',
							dataType: 'date | dateRange',
							repeatable: false,
							examples: ['1642', '1642-01-01', '1640/1645'],
							notes: 'Use ISO 8601 format when possible. For date ranges, use EDTF format (e.g., "1640/1645").'
						}
					},
					{
						id: 'dc_type',
						name: 'dc:type',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:type',
						mappedFrom: [{ field: 'object_name' }],
						documentation: {
							description: 'The nature or genre of the resource.',
							dataType: 'string | URI',
							repeatable: true,
							vocabulary: 'http://vocab.getty.edu/aat/',
							examples: ['schilderij', 'painting', 'http://vocab.getty.edu/aat/300177435'],
							notes: 'Use terms from the AAT (Art & Architecture Thesaurus) when possible.'
						}
					},
					{
						id: 'dc_format',
						name: 'dc:format',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:format',
						mappedFrom: [{ field: 'material' }],
						documentation: {
							description: 'The file format, physical medium, or dimensions of the resource.',
							dataType: 'string',
							repeatable: true,
							examples: ['olieverf op doek', 'oil on canvas', '363 x 437 cm'],
							notes: 'Include materials, techniques, and dimensions. Separate multiple values.'
						}
					},
					{
						id: 'dc_subject',
						name: 'dc:subject',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:subject',
						mappedFrom: [{ field: 'subject' }],
						documentation: {
							description: 'The topic of the resource.',
							dataType: 'string | URI',
							repeatable: true,
							vocabulary: 'http://iconclass.org/',
							examples: ['schuttersstuk', 'group portrait', 'http://iconclass.org/45C12'],
							notes: 'Use Iconclass for iconographic subjects, AAT for object types, or other relevant controlled vocabularies.'
						}
					},
					{
						id: 'dc_rights',
						name: 'dc:rights',
						path: '/rdf:RDF/edm:ProvidedCHO/dc:rights',
						documentation: {
							description: 'Information about rights held in and over the resource.',
							dataType: 'string | URI',
							repeatable: true,
							examples: ['Public Domain', 'Rijksmuseum Amsterdam'],
							notes: 'For standardized rights statements, use edm:rights on the Aggregation instead.'
						}
					},
					{
						id: 'edm_type',
						name: 'edm:type',
						path: '/rdf:RDF/edm:ProvidedCHO/edm:type',
						mappedFrom: [{ field: 'object_category' }],
						documentation: {
							description: 'The Europeana material type of the resource.',
							dataType: 'enum',
							required: true,
							repeatable: false,
							examples: ['IMAGE', 'TEXT', 'SOUND', 'VIDEO', '3D'],
							notes: 'Must be one of: IMAGE, TEXT, SOUND, VIDEO, 3D. This determines how the object is displayed in Europeana.'
						}
					}
				]
			},
			{
				id: 'webResource',
				name: 'edm:WebResource',
				path: '/rdf:RDF/edm:WebResource',
				documentation: {
					description: 'A web resource representing a digital view or representation of the Cultural Heritage Object.',
					notes: 'Each WebResource should have a unique URI as its rdf:about attribute. Multiple WebResources can be associated with a single ProvidedCHO.'
				},
				children: [
					{
						id: 'wr_about',
						name: 'rdf:about',
						path: '/rdf:RDF/edm:WebResource/@rdf:about',
						isAttribute: true,
						mappedFrom: [{ field: 'reproduction/reference' }],
						documentation: {
							description: 'The URI of the web resource (typically an image URL).',
							dataType: 'URI',
							required: true,
							examples: ['https://example.org/images/SK-A-1234.jpg'],
							notes: 'Must be a valid, resolvable URL that returns the digital representation.'
						}
					},
					{
						id: 'dc_format_wr',
						name: 'dc:format',
						path: '/rdf:RDF/edm:WebResource/dc:format',
						documentation: {
							description: 'The MIME type of the web resource.',
							dataType: 'string',
							repeatable: true,
							examples: ['image/jpeg', 'image/png', 'application/pdf'],
							notes: 'Use standard MIME types. For images, typically image/jpeg or image/png.'
						}
					},
					{
						id: 'edm_rights',
						name: 'edm:rights',
						path: '/rdf:RDF/edm:WebResource/edm:rights',
						documentation: {
							description: 'The rights statement for the web resource.',
							dataType: 'URI',
							required: true,
							vocabulary: 'http://rightsstatements.org/',
							examples: [
								'http://creativecommons.org/publicdomain/mark/1.0/',
								'http://rightsstatements.org/vocab/InC/1.0/'
							],
							notes: 'Must use a standardized rights statement URI from Creative Commons or RightsStatements.org.'
						}
					}
				]
			},
			{
				id: 'aggregation',
				name: 'ore:Aggregation',
				path: '/rdf:RDF/ore:Aggregation',
				documentation: {
					description: 'Groups together the digital representations and metadata about a Cultural Heritage Object provided by a single data provider.',
					required: true,
					notes: 'The Aggregation connects the ProvidedCHO with its WebResources and provider information.'
				},
				children: [
					{
						id: 'agg_about',
						name: 'rdf:about',
						path: '/rdf:RDF/ore:Aggregation/@rdf:about',
						isAttribute: true,
						documentation: {
							description: 'The URI identifying this aggregation.',
							dataType: 'URI',
							required: true,
							examples: ['https://data.europeana.eu/aggregation/provider/123'],
							notes: 'Should be a unique, dereferenceable URI for this aggregation.'
						}
					},
					{
						id: 'edm_aggregatedCHO',
						name: 'edm:aggregatedCHO',
						path: '/rdf:RDF/ore:Aggregation/edm:aggregatedCHO',
						documentation: {
							description: 'Reference to the Cultural Heritage Object being aggregated.',
							dataType: 'URI',
							required: true,
							examples: ['https://data.europeana.eu/item/123/456'],
							notes: 'Must point to the rdf:about of the corresponding edm:ProvidedCHO.'
						}
					},
					{
						id: 'edm_dataProvider',
						name: 'edm:dataProvider',
						path: '/rdf:RDF/ore:Aggregation/edm:dataProvider',
						mappedFrom: [{ field: 'collection' }],
						documentation: {
							description: 'The organization that contributed the metadata and content.',
							dataType: 'string | URI',
							required: true,
							examples: ['Rijksmuseum', 'http://data.europeana.eu/organization/123'],
							notes: 'Preferably use a URI from an authority file. This is the institution that owns or curates the object.'
						}
					},
					{
						id: 'edm_provider',
						name: 'edm:provider',
						path: '/rdf:RDF/ore:Aggregation/edm:provider',
						documentation: {
							description: 'The organization that delivers data to Europeana.',
							dataType: 'string | URI',
							required: true,
							examples: ['Digitale Collectie', 'Europeana Foundation'],
							notes: 'Often an aggregator organization. May be the same as edm:dataProvider for direct providers.'
						}
					},
					{
						id: 'edm_isShownAt',
						name: 'edm:isShownAt',
						path: '/rdf:RDF/ore:Aggregation/edm:isShownAt',
						documentation: {
							description: 'URL of a web page showing the object in its original context.',
							dataType: 'URI',
							examples: ['https://www.rijksmuseum.nl/collection/SK-A-1234'],
							notes: 'Should link to the provider\'s website page for this object. Either isShownAt or isShownBy is required.'
						}
					},
					{
						id: 'edm_isShownBy',
						name: 'edm:isShownBy',
						path: '/rdf:RDF/ore:Aggregation/edm:isShownBy',
						mappedFrom: [{ field: 'reproduction/reference' }],
						documentation: {
							description: 'URL of a web resource directly representing the object (e.g., image file).',
							dataType: 'URI',
							examples: ['https://example.org/images/SK-A-1234.jpg'],
							notes: 'Should point directly to a media file. Either isShownAt or isShownBy is required.'
						}
					},
					{
						id: 'edm_object',
						name: 'edm:object',
						path: '/rdf:RDF/ore:Aggregation/edm:object',
						documentation: {
							description: 'URL of a thumbnail or preview image for the object.',
							dataType: 'URI',
							examples: ['https://example.org/thumbnails/SK-A-1234_thumb.jpg'],
							notes: 'Used for preview displays. Recommended size is 200x200 pixels or larger.'
						}
					},
					{
						id: 'edm_rights_agg',
						name: 'edm:rights',
						path: '/rdf:RDF/ore:Aggregation/edm:rights',
						documentation: {
							description: 'The rights statement applying to the digital representations in this aggregation.',
							dataType: 'URI',
							required: true,
							vocabulary: 'http://rightsstatements.org/',
							examples: [
								'http://creativecommons.org/publicdomain/zero/1.0/',
								'http://rightsstatements.org/vocab/CNE/1.0/'
							],
							notes: 'Applies to the digital representations. Use standardized rights statements. This is the main rights statement shown to users.'
						}
					}
				]
			}
		]
	}
];

// Sample input records - museum collection data
export const sampleRecords: SampleRecord[] = [
	{
		priref: 'AM-12345',
		object_number: 'SK-A-1234',
		title: 'De Nachtwacht',
		_attr: { lang: 'nl' },
		description: 'Schutters van wijk II onder leiding van kapitein Frans Banninck Cocq en luitenant Willem van Ruytenburch, bekend als De Nachtwacht',
		maker: {
			name: 'Rembrandt van Rijn',
			role: 'schilder',
			birth_date: '1606',
			death_date: '1669'
		},
		dating: {
			'date.early': '1642',
			'date.late': '1642',
			period: '17de eeuw'
		},
		material: 'olieverf op doek',
		technique: 'schilderen',
		dimensions: {
			type: 'hoogte',
			value: '363',
			unit: 'cm'
		},
		collection: 'Rijksmuseum',
		object_category: 'schilderij',
		object_name: 'schuttersstuk',
		subject: 'schutters',
		reproduction: {
			reference: 'https://lh3.googleusercontent.com/J-mxAE7CPu-DXIOx4QKBtb0GC4QT',
			format: 'image/jpeg',
			type: 'digitale reproductie'
		}
	},
	{
		priref: 'AM-67890',
		object_number: 'SK-A-4691',
		title: 'Meisje met de parel',
		_attr: { lang: 'nl' },
		description: 'Tronie van een meisje met een tulband en een grote parel aan haar oor',
		maker: {
			name: 'Johannes Vermeer',
			role: 'schilder',
			birth_date: '1632',
			death_date: '1675'
		},
		dating: {
			'date.early': '1665',
			'date.late': '1667',
			period: '17de eeuw'
		},
		material: 'olieverf op doek',
		technique: 'schilderen',
		dimensions: {
			type: 'hoogte',
			value: '44.5',
			unit: 'cm'
		},
		collection: 'Mauritshuis',
		object_category: 'schilderij',
		object_name: 'tronie',
		subject: 'portret',
		reproduction: {
			reference: 'https://upload.wikimedia.org/wikipedia/commons/0/0f/1665_Girl_with_a_Pearl_Earring.jpg',
			format: 'image/jpeg',
			type: 'digitale reproductie'
		}
	},
	{
		priref: 'AM-11111',
		object_number: 'SK-C-5',
		title: 'De Staalmeesters',
		_attr: { lang: 'nl' },
		description: 'De waardijns van het Amsterdamse lakenbereidersgilde, bekend als De Staalmeesters',
		maker: {
			name: 'Rembrandt van Rijn',
			role: 'schilder',
			birth_date: '1606',
			death_date: '1669'
		},
		dating: {
			'date.early': '1662',
			'date.late': '1662',
			period: '17de eeuw'
		},
		material: 'olieverf op doek',
		technique: 'schilderen',
		dimensions: {
			type: 'hoogte',
			value: '191.5',
			unit: 'cm'
		},
		collection: 'Rijksmuseum',
		object_category: 'schilderij',
		object_name: 'groepsportret',
		subject: 'gildeleden',
		reproduction: {
			reference: 'https://upload.wikimedia.org/wikipedia/commons/3/33/Rembrandt_-_De_Staalmeesters.jpg',
			format: 'image/jpeg',
			type: 'digitale reproductie'
		}
	},
	{
		priref: 'AM-22222',
		object_number: 'BK-NM-3888',
		title: 'Melkmeisje',
		_attr: { lang: 'nl' },
		description: 'Een dienstmeid die melk uitgiet in een aardewerken kom',
		maker: {
			name: 'Johannes Vermeer',
			role: 'schilder',
			birth_date: '1632',
			death_date: '1675'
		},
		dating: {
			'date.early': '1658',
			'date.late': '1660',
			period: '17de eeuw'
		},
		material: 'olieverf op doek',
		technique: 'schilderen',
		dimensions: {
			type: 'hoogte',
			value: '45.5',
			unit: 'cm'
		},
		collection: 'Rijksmuseum',
		object_category: 'schilderij',
		object_name: 'genrestuk',
		subject: 'huishoudelijk werk',
		reproduction: {
			reference: 'https://upload.wikimedia.org/wikipedia/commons/2/20/Johannes_Vermeer_-_Het_melkmeisje.jpg',
			format: 'image/jpeg',
			type: 'digitale reproductie'
		}
	},
	{
		priref: 'AM-33333',
		object_number: 'SK-A-2344',
		title: 'Zelfportret als de apostel Paulus',
		_attr: { lang: 'nl' },
		description: 'Rembrandt als de apostel Paulus, met een zwaard en manuscripten',
		maker: {
			name: 'Rembrandt van Rijn',
			role: 'schilder',
			birth_date: '1606',
			death_date: '1669'
		},
		dating: {
			'date.early': '1661',
			'date.late': '1661',
			period: '17de eeuw'
		},
		material: 'olieverf op doek',
		technique: 'schilderen',
		dimensions: {
			type: 'hoogte',
			value: '91',
			unit: 'cm'
		},
		collection: 'Rijksmuseum',
		object_category: 'schilderij',
		object_name: 'zelfportret',
		subject: 'apostel Paulus',
		reproduction: {
			reference: 'https://upload.wikimedia.org/wikipedia/commons/b/b6/Rembrandt_self-portrait_apostle_Paul.jpg',
			format: 'image/jpeg',
			type: 'digitale reproductie'
		}
	}
];

// Transform a sample record to EDM output based on mappings
export function transformRecord(record: SampleRecord): OutputRecord {
	const maker = record.maker as SampleRecord | undefined;
	const dating = record.dating as SampleRecord | undefined;
	const reproduction = record.reproduction as SampleRecord | undefined;

	// Determine edm:type based on object_category
	let edmType = 'IMAGE';
	const category = (record.object_category as string)?.toLowerCase();
	if (category === 'schilderij' || category === 'painting' || category === 'tekening' || category === 'prent') {
		edmType = 'IMAGE';
	} else if (category === 'beeld' || category === 'sculpture') {
		edmType = '3D';
	} else if (category === 'document' || category === 'manuscript') {
		edmType = 'TEXT';
	}

	return {
		'@context': 'http://www.europeana.eu/schemas/edm/',
		'@type': 'edm:ProvidedCHO',
		'dc:identifier': record.object_number as string,
		'dc:title': {
			'@value': record.title as string,
			'@language': record._attr?.lang || 'nl'
		},
		'dc:description': record.description as string,
		'dc:creator': maker?.name as string,
		'dcterms:created': dating?.['date.early'] as string,
		'dc:format': record.material as string,
		'dc:type': record.object_name as string,
		'dc:subject': record.subject as string,
		'edm:type': edmType,
		'edm:dataProvider': record.collection as string,
		'edm:isShownBy': reproduction?.reference as string
	};
}

// Convert record to XML string for display
export function recordToXml(record: SampleRecord, indent: number = 0): string {
	const spaces = '  '.repeat(indent);
	let xml = '';

	const renderValue = (key: string, value: unknown, currentIndent: number): string => {
		const sp = '  '.repeat(currentIndent);
		if (key === '_attr') return '';

		if (typeof value === 'string') {
			return `${sp}<${key}>${escapeXml(value)}</${key}>\n`;
		} else if (typeof value === 'object' && value !== null) {
			const obj = value as SampleRecord;
			const attrs = obj._attr
				? Object.entries(obj._attr)
						.map(([k, v]) => ` ${k}="${escapeXml(v)}"`)
						.join('')
				: '';
			let inner = '';
			for (const [k, v] of Object.entries(obj)) {
				if (k !== '_attr') {
					inner += renderValue(k, v, currentIndent + 1);
				}
			}
			if (inner) {
				return `${sp}<${key}${attrs}>\n${inner}${sp}</${key}>\n`;
			}
			return `${sp}<${key}${attrs} />\n`;
		}
		return '';
	};

	// Handle top-level record with potential lang attribute
	const lang = record._attr?.lang ? ` lang="${record._attr.lang}"` : '';

	xml += `${spaces}<record${lang}>\n`;
	for (const [key, value] of Object.entries(record)) {
		if (key !== '_attr') {
			xml += renderValue(key, value, indent + 1);
		}
	}
	xml += `${spaces}</record>`;

	return xml;
}

function escapeXml(str: string): string {
	return str
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&apos;');
}

/**
 * Get a value from a record based on a source path like /record/maker/name
 */
export function getValueFromPath(record: SampleRecord, path: string): string | undefined {
	// Remove /record prefix
	const cleanPath = path.replace(/^\/record\/?/, '');
	if (!cleanPath) return undefined;

	const parts = cleanPath.split('/').filter(Boolean);
	let value: unknown = record;

	for (const part of parts) {
		if (value && typeof value === 'object') {
			// Handle attribute paths like @lang
			const cleanPart = part.startsWith('@') ? part.slice(1) : part;
			// Handle special paths like date.early
			value = (value as Record<string, unknown>)[cleanPart];
		} else {
			return undefined;
		}
	}

	if (typeof value === 'string') {
		return value;
	} else if (typeof value === 'number') {
		return String(value);
	}
	return undefined;
}

/**
 * Transform a record using the current mappings to generate output.
 * This simulates the Groovy mapping execution for the preview.
 */
export function transformRecordWithMappings(
	record: SampleRecord,
	mappings: Mapping[]
): OutputRecord {
	// Start with empty output structure
	const output: OutputRecord = {
		'@context': 'http://www.europeana.eu/schemas/edm/',
		'@type': 'edm:ProvidedCHO'
	};

	// Group mappings by target to handle multiple sources mapping to same target
	const mappingsByTarget = new Map<string, Mapping[]>();
	for (const mapping of mappings) {
		const existing = mappingsByTarget.get(mapping.targetPath) || [];
		existing.push(mapping);
		mappingsByTarget.set(mapping.targetPath, existing);
	}

	// Apply each mapping
	for (const [targetPath, targetMappings] of mappingsByTarget) {
		// Get all values for this target (could be multiple sources)
		const values: string[] = [];
		for (const mapping of targetMappings) {
			const value = getValueFromPath(record, mapping.sourcePath);
			if (value !== undefined) {
				values.push(value);
			}
		}

		if (values.length > 0) {
			// Convert target path to output key
			// e.g., /rdf:RDF/edm:ProvidedCHO/dc:title -> dc:title
			const pathParts = targetPath.split('/').filter(Boolean);
			const targetKey = pathParts[pathParts.length - 1];

			// If multiple values, join with semicolon, otherwise use single value
			const outputValue = values.length === 1 ? values[0] : values;

			// Handle special cases like @rdf:about (attribute)
			if (targetKey.startsWith('@')) {
				// Attribute - store in special format
				const cleanKey = targetKey.slice(1);
				output[cleanKey] = outputValue;
			} else {
				// Regular element
				output[targetKey] = outputValue;
			}
		}
	}

	// If no edm:type mapping exists, derive from object_category
	if (!output['edm:type']) {
		const category = (record.object_category as string)?.toLowerCase();
		if (
			category === 'schilderij' ||
			category === 'painting' ||
			category === 'tekening' ||
			category === 'prent'
		) {
			output['edm:type'] = 'IMAGE';
		} else if (category === 'beeld' || category === 'sculpture') {
			output['edm:type'] = '3D';
		} else if (category === 'document' || category === 'manuscript') {
			output['edm:type'] = 'TEXT';
		} else {
			output['edm:type'] = 'IMAGE';
		}
	}

	return output;
}
