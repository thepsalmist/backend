# Custom Media Cloud rss feeds RFC 

- Feature Name: Support fetching stories from news websites that don't have (active) rss feeds
- Start Date: 2021-09-2

# Summary

[summary]: #summary
Generate rss feeds from websites without rss feeds and enable civicSignal mediacloud to pickup the feeds.

# Motivation

[motivation]: #motivation
As of now, mediacloud is able to pull stories from websites with active rss feeds. However, some websites have inactive rss feeds or don't have any at all. Since these websites still upload new content, it would be nice if we could have a system that scrapes data off the websites and generates rss feeds that can then be passed onto mediacloud for processing.

# Guide-level explanation

[guide-level-explanation]: #guide-level-explanation

Assuming we are hosting an rss feed at https://nigeria.civicsignal.africa/gurdian_ng.rss ,this URL is what will be added to the Nigeria collection as a media source. MediaCloud will make requests to this URL where It will receive updated rss feeds.

# Reference-level explanation

[reference-level-explanation]: #reference-level-explanation
To be able to achieve the above functionality, we will need
1. A webScrapper that scrapes news off a website and converts the content into an rss file.
2. A way to store the rss file such that it can be independently accessed by mediaCloud and independently updated on daily basis by the scrapper. One option would be to store the file in an s3 bucket.
3. A service that maps an incoming request e.g a request for `gurdian_ng.rss` to the appropriate s3 object.
4.  
So the whole process will be
- Create a webScrapper that converts it's content into an rss file.
- Upload the rss file to s3 bucket
- Create a service that receives requests(proxy), extracts a requested url path and checks if the requested object exists in s3. If it does, the service returns the rss file.
- 
**NOTE**
Instead of using s3, the service matching requests to s3 objects could also be converted into an API that receives files from different scrappers and stores them locally and also serves them once requested. Although this is a valid option, I still think storing the files out of a local filesystem is a much better approach. 

# Drawbacks

[drawbacks]: #drawbacks

1.Given that different websites have different structures, it will be infeasible to create scrappers for all websites lacking rss feeds.

# Rationale and alternatives

[rationale-and-alternatives]: #rationale-and-alternatives

- Why is this design the best in the space of possible designs?

  > 1. The fact that we will not have to write a new feed handler for crawler-fetcher. This means we won't have to make modifications to main code.

- What other designs have been considered and what is the rationale for not choosing them?
  > Adding a new clawler for every website we want to get stories from. Something like [this](https://github.com/mediacloud/backend/tree/04bc9c63b55a20ab4f08aed2bef599bf94cd7474/apps/crawler-ap) since it's not scallable. 
  
# Prior art

# Unresolved questions

# Future possibilities

Can't think of any.
