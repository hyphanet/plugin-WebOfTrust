#!/bin/bash
set -o nounset
set -o errexit
set -o errtrace
trap 'echo "Error at line $LINENO, exit code $?" >&2' ERR

echo "Installing pyFreenet3..."
pip3 install -U --user --egg pyFreenet3

FILENAME="WebOfTrust-$(git describe)-built-on-$TRAVIS_JDK_VERSION.jar"
URI="CHK@/$FILENAME"

echo "Uploading WoT JAR to $URI..."
# TODO: As of 2018-05-30 fcpuploads "--timeout" doesn't work, using coreutils' timeout, try again later
if ! timeout 10m fcpupload --spawn --fcpPort 9486 "$URI" "dist/WebOfTrust.jar" ; then
	echo "Uploading WebOfTrust.jar to Freenet failed! Dumping wrapper.log, wrapper.conf and listing node dir..." >&2
	echo "wrapper.log:" >&2
	cat "$HOME/.local/share/babcom-spawn-9486/wrapper.log" >&2
	echo "wrapper.conf:" >&2
	cat "$HOME/.local/share/babcom-spawn-9486/wrapper.conf" >&2
	echo "Node dir:" >&2
	ls -alR "$HOME/.local/share/babcom-spawn-9486/" >&2
	exit 1
fi

exit 0
