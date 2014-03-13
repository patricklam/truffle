# GraalVM Changelog

## `tip`
### Graal
* New methods for querying memory usage of individual objects and object graphs in Graal API (MetaAccessProvider#getMemorySize, MetaUtil#getMemorySizeRecursive).
* New (tested) invariant that equality comparisons for JavaType/JavaMethod/JavaField values use .equals() instead of '=='.
* Made graph caching compilation-local.

### Truffle
* ...

## Version 0.1
5-Feb-2014, [Repository Revision](http://hg.openjdk.java.net/graal/graal/rev/b124e22eb772)

### Graal

* Initial version of a dynamic Java compiler written in Java.
* Support for multiple co-existing GPU backends ([GRAAL-1](https://bugs.openjdk.java.net/browse/GRAAL-1)).
* Fixed a compiler bug when running RuneScape ([GRAAL-7](https://bugs.openjdk.java.net/browse/GRAAL-7)).
* Bug fixes ([GRAAL-4](https://bugs.openjdk.java.net/browse/GRAAL-4), [GRAAL-5](https://bugs.openjdk.java.net/browse/GRAAL-5)).

### Truffle

* Initial version of a multi-language framework on top of Graal.
* Update of the [Truffle Inlining API](http://mail.openjdk.java.net/pipermail/graal-dev/2014-January/001516.html).
