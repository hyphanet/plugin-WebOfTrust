COPYRIGHT
=========

WOT copyright is held by Freenet Project Incorporated.  WOT is
distributed under the GPL license. You can find it in the file called
"gpl.txt".
----------------

WOT
Copyright (C) 2008-2014 Freenet Project Incorporated

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

COMPILATION
===========

In order to compile WOT, you need to obtain the source code of Freenet ("fred") and WOT:
https://github.com/freenet/fred-staging
https://github.com/freenet/plugin-WoT-staging

The "staging" repositories are the latest development versions. Replace staging with "official" to get only
the latest official release versions.

We recommend using Eclipse: The repositories ship with an Eclipse project configuration.
To use Eclipse:
- Add the EGit plugin to Eclipse so you don't have to use command-line Git. Newer Eclipse versions 
(4.2 aka Kepler at least) ship with it by default.
-  When having EGit installed, use "File" / "Import" in Eclipse to import the Git projects.
- Rename the "fred-staging" project in Eclipse to "fred" and the "plugin-WoT-staging"
project to "WebOfTrust". Renaming can be done by right-clicking a project, selecting "Refactor", then "Rename".
- Create a file called "override.properties" in the fred project. Write 'lib.contrib.get = true' into it.
This will make the builder download the latest official freenet-ext.jar so you don't have to compile it yourself.
- Download the Bouncycastle crypto library from www.bouncycastle.org and store it as "fred/lib/bcprov.jar"
If you have a working Freenet installation, you can also just copy it from your Freenet directory.
It might be called something similar to "bcprov-jdk15on-149.jar".

Now building should work using Eclipse's "Project" menu. 
We recommend disabling "Build automatically" in the menu because compiling Fred and WOT can take 
a long time and therefore it's better to do it manually only when you need it.

If you don't want to use Eclipse but instead want to compile manually from the shell, it can be done like this:
  $ git clone https://github.com/freenet/fred-staging.git fred
  $ cd fred
  $ echo 'lib.contrib.get = true' >> override.properties
  # Download the Bouncycastle crypto library from www.bouncycastle.org and put it in lib/bcprov.jar
  # If you have a working Freenet installation, you can also just copy it from your Freenet directory.
  # It might be called something similar to "bcprov-jdk15on-149.jar".
  $ ant
  $ cd ..
  $ git clone https://github.com/freenet/plugin-WoT-staging.git WebOfTrust
  $ cd WebOfTrust
  $ ant


RUNNING
=======

Visit the plugins page ("http://127.0.0.1:8888/plugins/ by default) with your Web browser.

In the Load Plugin box, enter: /your-eclipse-workspace-path/WebOfTrust/dist/WebOfTrust.jar and click the Load button.

After the plugin is loaded, WOT will be accessible at the "Community" menu of your Freenet web interface.

UNDERSTANDING
=============

See https://wiki.freenetproject.org/Web_of_Trust


DEVELOPMENT
===========

See the files in the "developer-documentation" folder.

Also, see the following pages in the Freenet wiki:
https://wiki.freenetproject.org/Web_of_Trust
https://wiki.freenetproject.org/Web_Of_Trust_development
https://wiki.freenetproject.org/Plugin_development_tutorial
