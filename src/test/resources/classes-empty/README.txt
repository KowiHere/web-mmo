Deliberately holds no class definitions.

A missing directory and an empty one are different failures: the first is
"nobody put the content anywhere", which the resolver reports by itself, and
the second is "the content is there and says nothing", which is the one the
loader has to catch. This directory exists so that the second can be tested.
