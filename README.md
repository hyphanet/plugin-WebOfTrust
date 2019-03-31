[![Build status of branch master on freenet's repository](https://travis-ci.org/freenet/plugin-WebOfTrust.svg?branch=master "Build status of branch master on freenet's repository")](https://travis-ci.org/freenet/plugin-WebOfTrust/builds)
[![Build status of branch master on xor-freenet's repository](https://travis-ci.org/xor-freenet/plugin-WebOfTrust.svg?branch=master "Build status of branch master on xor-freenet's repository")](https://travis-ci.org/xor-freenet/plugin-WebOfTrust/builds)
[![Build status of branch next on freenet's repository](https://travis-ci.org/freenet/plugin-WebOfTrust.svg?branch=next "Build status of branch next on freenet's repository")](https://travis-ci.org/freenet/plugin-WebOfTrust/builds)
[![Build status of branch next on xor-freenet's repository](https://travis-ci.org/xor-freenet/plugin-WebOfTrust.svg?branch=next "Build status of branch next on xor-freenet's repository")](https://travis-ci.org/xor-freenet/plugin-WebOfTrust/builds)

## Web of Trust - a collaborative spam filter for Freenet

The [Freenet](https://freenetproject.org) plugin Web of Trust (WoT) tries to solve the problem of
spam being an important threat to address in an anonymous, censorship-resistant network:  
Where an attacker cannot take down content they will attempt to get rid of it by drowning it in
spam.

Conventional spam filters cannot work in such an environment:
- An attacker is anonymous like everyone else so they cannot be blocked by e.g. an IP address.
- Because Freenet is a peer-to-peer network its available bandwidth is scarce and thus spam must
  not even be downloaded before filtering it out in order to avoid
  [denial of service](https://en.wikipedia.org/wiki/Denial-of-service_attack) - filtering spam by
  e.g. lists of bad words won't work.

WoT deals with these issues by allowing each user to create so-called _identities_ which can assign
_trust values_ to the identities of other users.  
These constitute a democratic vote among users, the result decides if a particular identity is
considered as legitimate or as a spammer. The content of spammers is completely ignored then, it
won't cause any network traffic.

While WoT does have a user interface of its own which can be used to manage identites and trusts,
it is intended to be used as a general-purpose library to allow actual Freenet applications to
be built upon it. As of 2019 these are:
- [Sone](https://github.com/Bombe/Sone) - social networking
- [FlogHelper](https://github.com/freenet/plugin-FlogHelper) - blogging
- [Freemail](https://github.com/freenet/plugin-Freemail) - email
- [Freetalk](https://github.com/freenet/plugin-Freetalk) - forum systems

For an in-depth explanation of how WoT works see the [whitepaper / core developer's manual](developer-documentation/core-developers-manual/OadSFfF-version1.2-non-print-edition.pdf).

### Compiling

#### Dependencies

Clone the [fred](https://github.com/freenet/fred) and plugin-WebOfTrust repositories into the same
parent directory.  
Compile fred using its instructions.

#### Compiling by command line

```bash
# With the Ant build script reference implementation:
ant
# If you get errors about missing classes check build.xml for whether the JAR locations are correct.

# With the new Gradle builder - it is fully tested against Ant (see tools/) but lacks some features.
# Its advantages are:
# - parallel unit test execution on all available CPU cores.
# - incremental builds are supported (if you don't run "gradle clean" before).
gradle clean
gradle
```

The output `WebOfTrust.jar` will be in the `dist` directory.  
You can load it on the `Plugins` page of the Freenet web interface.  

##### Additional compilation options

```bash
# Compile and produce test coverage and code complexity statistics as HTML to test-coverage/
sudo apt install cobertura
ant -Dtest.coverage=true
# Skip unit tests.
ant -Dtest.skip=true # With Ant
gradle -x test       # With Gradle
# Run a single unit test.
ant -Dtest.class=plugins.WebOfTrust.CLASSNAME
# Benchmark all unit tests and produce sorted output to figure out the slowest ones
tools/benchmark-unit-tests
# Benchmark a single unit test and produce average runtime to improve it
tools/benchmark-unit-test TEST_CLASS TEST_FUNCTION NUMBER_OF_ITERATIONS
```

#### Compiling with Eclipse

* Import the project configurations which fred and WoT ship in Eclipse.  
  **NOTICE:** As of 2018-07 fred currently does not ship one, you can use an old release for now.
  The newest which still includes the project can be obtained with:  
  	`git checkout build01480`  
  Be aware that its build instructions will be different compared to newer releases, so check the
  `README.md` after the above command.
* Since build01480 does not automatically download its dependencies, get them from an existing
  Freenet installation:
  * Put `freenet-ext.jar` in `fred/lib/freenet`
  * Put `bcprov.jar` (from e.g. `bcprov-jdk15on-149.jar`, name may vary) in `fred/lib`.
* If necessary fix the build paths for your Eclipse projects so they refer to the correct JAR paths.
* Disable automatic building in Eclipse's `Project` menu as the Ant builders take quite a bit of time to execute.

Now building should work using the `Project` menu or toolbar buttons.

### Debugging

Run fred's class `freenet.node.NodeStarter` using the Eclipse debugger.  
Browse to Freenet's [Plugins page](http://127.0.0.1:8888/plugins/).  
Use the `Load Plugin` box to load `PARENT_DIRECTORY/plugin-WebOfTrust/dist/WebOfTrust.jar`.  
After the plugin is loaded, WoT will be accessible at the `Community` menu.  
Read [the debugging instructions](developer-documentation/Debugging.txt) for further details.

#### Database analysis

Do **not** use the following tool upon your database while Freenet is running!  
**Backup your database** before using it!

```bash
# Validate semantic integrity of the database and recompute all score values (= "computed trust" in the UI).
# This currently is mostly of diagnostic character for development purposes, it is unlikely to fix your
# database if WoT does not start, sorry.
tools/wotutil -testAndRepair DATABASE_FILE
# Execute a "Freenet Client Protocol" call upon the database.
# FCP is the protocol which applications built upon WoT use to access its API.
# For available functions see src/plugins/WebOfTrust/ui/fcp/FCPInterface.java
tools/wotutil -fcp DATABASE_FILE Message=WOT_FCP_CALL key1=value1 key2=value2 ...
```

### Development

See:
- the [whitepaper / core developer's manual](developer-documentation/core-developers-manual/OadSFfF-version1.2-non-print-edition.pdf).
- the files in the [developer-documentation](developer-documentation) directory.
- https://github.com/freenet/wiki/wiki/Web-of-Trust
- https://github.com/freenet/wiki/wiki/Web-Of-Trust-Development
- https://github.com/freenet/wiki/wiki/Plugin-Development-Tutorial
