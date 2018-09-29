#!/bin/bash
set -o nounset
set -o errexit
set -o errtrace
trap 'echo "Error at line $LINENO, exit code $?" >&2' ERR

FILENAME="WebOfTrust-$(git describe)-built-on-$TRAVIS_JDK_VERSION.jar"
URI="CHK@/$FILENAME"

echo "Installing pyFreenet3..."
pip3 install -U --user --egg pyFreenet3

# FIXME: As of 2018-05-03 fcpupload's "--spawn" parameter doesn't work,
# see https://bugs.freenetproject.org/view.php?id=7018 - so we start our
# own node.
# Once that is fixed don't start a node here - do so by reverting the
# commit which added this FIXME.

cd "$TRAVIS_BUILD_DIR"/../fred
echo "Configuring node..."

# FIXME: Use fred's official URL once there is one
wget https://github.com/ArneBab/lib-pyFreenet-staging/releases/download/spawn-ext-data/seednodes.fref

cat << EOF > freenet.ini
node.updater.enabled=false
node.clientCacheType=ram
node.storeType=ram
fproxy.hasCompletedWizard=true
security-levels.physicalThreatLevel=LOW
security-levels.networkThreatLevel=LOW
node.opennet.enabled=true
node.outputBandwidthLimit=1048576
node.inputBandwidthLimit=-1
node.opennet.maxOpennetPeers=65
node.load.threadLimit=1000
logger.priority=NONE
End
EOF

echo "Starting node..."
java -Xmx512M -classpath 'build/output/*' -Djna.nosys=true freenet.node.NodeStarter &
cd "$TRAVIS_BUILD_DIR"

echo "Giving node 60s to boot..."
sleep 60s

echo "Uploading WoT JAR to $URI..."

# Echo something every minute to prevent Travis from killing the job
# after 10 minutes of silence.
(
	minutes=0
	while sleep 1m ; do
		echo Minutes passed: $((++minutes))
	done
) &

# TODO: As of 2018-05-30 fcpupload's "--timeout" doesn't work, using coreutils' timeout, try again later
# TODO: As of 2018-05-30 fcpupload's "--compress" also doesn't work.
if ! time timeout 30m fcpupload --wait --realtime "$URI" "$TRAVIS_BUILD_DIR/dist/WebOfTrust.jar" ; then
	echo "Uploading WebOfTrust.jar to Freenet failed!" >&2
	
	# The commented out lines are for debugging fcpupload's "--spawn".
	#
	# echo "Uploading WebOfTrust.jar to Freenet failed! Dumping wrapper.log, wrapper.conf and listing node dir..." >&2
	# echo "wrapper.log:" >&2
	# cat "$HOME/.local/share/babcom-spawn-9486/wrapper.log" >&2
	# echo "wrapper.conf:" >&2
	# cat "$HOME/.local/share/babcom-spawn-9486/wrapper.conf" >&2
	# echo "Node dir:" >&2
	# ls -alR "$HOME/.local/share/babcom-spawn-9486/" >&2
	
	exit 1
fi

# Can't use "kill $(jobs -p)" as that fails if a job exits in between
jobs -p | xargs --no-run-if-empty kill || true

if ! wait ; then
	echo "Node exit code indicates error!" >&2
	exit 1
fi

exit 0
