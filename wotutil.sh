#!/bin/bash

java -Xmx1024M  -classpath ../fred/lib/freenet/freenet-ext.jar:../fred/dist/freenet.jar:dist/WebOfTrust.jar plugins.WebOfTrust.util.WOTUtil "$@"
