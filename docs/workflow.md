# Dataset Workflow

A dataset is created empty when it is given a name, and from that point onwards they have attributes which indicate its current state.  This is not recorded as a single state value but as a series of timestamps, each of which shows the time at which the dataset reached that particular state.  The reason for this approach is that Narthex is built to do periodic harvesting of source data, and it can optionally present an analysis of the data, so its state is multi-faceted.  

For example, after a periodic fetch of new records, the "source" has been updated, but a full analysis need not be done so the existing analysis can be maintained until a user wants it re-done (it will have only changed slightly).  However, upon finishing a partial fetch, it does make sense to process the new records and follow through to the final phase of saving the data (only the new data!) to the triple store.  This way, the work to be done after a fetch is minimal, yet at any time a full refresh of the analysis can be done.

This is useful for testing because it becomes possible to cause the cascade of analyzing or processing (long-lived processes) by simply deleting the timestamp indicating that the work has already been done.

### RAW

When the data is given to Narthex in source form (not harvested) it is not yet known what XML tag represents the record delimiter and which tag or attribute represents the record's unique identifier.  The data in this case is stored separately in raw form and an analysis is performed in preparation for the following state.

### RAW_ANALYZED

When a dataset is in the RAW state, the analysis is performed so that the user gains insight into the data and can choose the delimiter and unique identifier.  The analysis is rigorous, so the uniqueness of the values of a field can be assured.  Once the delimiter/unique-id choices have been made, a source repository with this information is created and the data is imported there.

### SOURCED

A dataset is sourced when it has a source repository, which either contains the provided source file, or a series of files corresponding to an initial full harvest followed by any number of periodic partial harvests which have been subsequently done.  Each harvest results in a new file added to the source repository, yet the data contents of the repository are read as if they were a single file.  The source repository effectively replaces old records with new ones before passing its contents to the other processes.

### MAPPABLE

Whenever a source repository is updated (a new set of changed records is fetched from the source system) its data contents are packed into a new SIP-Zip file with all the necessary data (metadata information, target record definition, validation schema) which can then be downloaded and mapped by the SIP-Creator.  When the SIP-Creator starts up, it fetches the list of prepared SIP-Zip files so that they can be downloaded and mapping can proceed.  

When mapping and validation is completed in the SIP-Creator, a newly-created SIP-Zip file can be uploaded to Narthex.  The source data is used in the SIP-Creator but it is not uploaded in the SIP-Zip file because this same source is already present within Narthex.

### PROCESSABLE

A dataset is processable if an uploaded SIP-Zip file is present, because it contains all the information necessary for generating the mapped output.  Typically the SIP-Zip is uploaded from the SIP-Creator after mapping, but to facilitate data migration and testing it is also possible to upload a SIP-Zip file directly to Narthex.  In the latter case, the dataset can be taken through all of the phases of its workflow without involving mapping in the SIP-Creator, since this mapping has already been performed to create the uploaded "migration file".

### PROCESSED

When the data has been mapped and validated using the SIP-Creator mapping engine (integrated into Narthex), it appears in the form of RDF/XML in the mapped data repository.  Mapped data will initially be a single (perhaps large) file, but after each partial fetch of changed records, it will contain a series of processed files.  Similar to the source repository, the mapped repository's files are 

### ANALYZED

Once the processing of a dataset is completed, Narthex can perform its analysis process, where the values from each individual field or path within the source data is separated from the rest, sorted, collated, and counted.  The result of this analysis provides the user with a view of all the values that have ever appeared in a given field, which is the basis for both terminology mapping and category mapping.  These values appear in the form of an alphanumerically sorted list of unique values, and a histogram of value counts from the highest to the lowest.

### SAVED

Narthex saves the processed RDF data to the associated triple store. Initially this is done in one bulk operation, but after a fetching changed records, only the changed need to be saved.  Either way, the timestamp associated with this state is updated.