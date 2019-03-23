## Web of Trust - a collaborative spam filter for Freenet

The [Freenet](https://freenetproject.org) plugin Web of Trust (WoT) tries to solve the problem of
spam being an important threat to address in an anonymous, censorship-resistant network:  
Where an attacker cannot take down content they will attempt to get rid of it by drowning it in
spam.

Conventional spam filters cannot work in such an environment:
- An attacker is anonymous like everyone else so they cannot be blocked by e.g. an IP address.
- Because Freenet is a peer-to-peer network its available bandwidth is scarce and thus spam must
  not even be downloaded before filtering it out to avoid
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

### Compiling

#### Dependencies

Clone the [fred](https://github.com/freenet/fred) and plugin-WebOfTrust repositories into the same
parent directory.  
Compile fred using its instructions.

#### Compiling by command line

```bash
ant clean
ant
# If you get errors about missing classes check build.xml for whether the JAR locations are correct.
```

The output `WebOfTrust.jar` will be in the `dist` directory.  
You can load it on the `Plugins` page of the Freenet web interface.  

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
Use the `Load Plugin` box to load `PARENT_DIRECTORY/WebOfTrust/dist/WebOfTrust.jar`.  
After the plugin is loaded, WoT will be accessible at the `Community` menu.  
Read [the debugging instructions](developer-documentation/Debugging.txt) for further details.

### Development

See:
- the files in the [developer-documentation](developer-documentation) directory.
- https://wiki.freenetproject.org/Web_of_Trust
- https://wiki.freenetproject.org/Web_Of_Trust_development
- https://wiki.freenetproject.org/Plugin_development_tutorial
