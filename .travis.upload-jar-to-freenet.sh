#!/bin/bash
set -o nounset
set -o errexit
set -o errtrace
trap 'echo "Error at line $LINENO, exit code $?" >&2' ERR

FILENAME="WebOfTrust-$(git describe --always)-built-on-$TRAVIS_JDK_VERSION.jar"
URI="CHK@/$FILENAME"
FCP_PORT=23874

echo "Uploading WoT JAR to $URI..."

# Echo something every minute to prevent Travis from killing the job
# after 10 minutes of silence.
(
	minutes=0
	while sleep 1m ; do
		echo Minutes passed: $((++minutes))
	done
) &

# TODO: As of 2018-05-30 fcpupload's "--compress" also doesn't work.
if ! time fcpupload --fcpPort=$FCP_PORT --wait --realtime \
		"$URI" "$TRAVIS_BUILD_DIR/dist/WebOfTrust.jar" ; then

	echo "Uploading WebOfTrust.jar to Freenet failed!" >&2
	
	# The commented out lines are for debugging fcpupload's "--spawn".
	#
	# echo "Uploading WebOfTrust.jar to Freenet failed! Dumping diagnostic data..." >&2
	# echo "Node stdout/stderr: " >&2
	# cat "$TRAVIS_BUILD_DIR"/../fred/freenet.WoT-JAR-upload-node.log >&2
	# echo "wrapper.log:" >&2
	# cat "$HOME/.local/share/babcom-spawn-9486/wrapper.log" >&2
	# echo "wrapper.conf:" >&2
	# cat "$HOME/.local/share/babcom-spawn-9486/wrapper.conf" >&2
	# echo "Node dir:" >&2
	# ls -alR "$HOME/.local/share/babcom-spawn-9486/" >&2
	
	exit 1
fi

# TODO: Use pyFreenet for this
echo "Stopping the Freenet node..."
xargs kill < "$TRAVIS_BUILD_DIR"/../fred/freenet.WoT-JAR-upload-node.pid || true

exit 0
