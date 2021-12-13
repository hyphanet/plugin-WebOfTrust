#!/bin/bash
set -o nounset
set -o errexit
set -o errtrace
trap 'echo "Error at line $LINENO, exit code $?" >&2' ERR

echo "Installing pyFreenet3..."
pip3 install -upgrade -user pyFreenet3

# FIXME: As of 2018-05-03 fcpupload's "--spawn" parameter doesn't work,
# see https://bugs.freenetproject.org/view.php?id=7018 - so we start our
# own node.
# Once that is fixed don't start a node here - do so by reverting the
# commit which added this FIXME.

if ! [ -e './build/output/freenet.jar' ] ; then
	echo "ERROR: Run this script in a dir which contains a compiled fred git repository!" >&2
	exit 1
fi

echo "Configuring node..."

# Ignore Travis cache since seednodes may change.
rm -f seednodes.fref
# FIXME: Use fred's official URL once there is one
wget https://github.com/ArneBab/lib-pyFreenet-staging/releases/download/spawn-ext-data/seednodes.fref

# Use a non-standard port to not interfere with WoT unit tests.
# (They should not be connecting to FCP by network, but let's be paranoid anyway.)
FCP_PORT=23874

cat << EOF > freenet.ini
fcp.port=$FCP_PORT
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
java -Xmx512M -classpath 'build/output/*' -Djna.nosys=true freenet.node.NodeStarter \
	&> freenet.WoT-JAR-upload-node.log &

jobs -p > freenet.WoT-JAR-upload-node.pid
