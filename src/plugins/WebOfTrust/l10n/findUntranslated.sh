#!/bin/sh

# The first argument is the l10n file to use.
# We assume l10n files are SimpleFieldSets.

if [ "$1" = "" ]
then
	echo "$0: need a l10n file as first argument.";
	exit 1
fi

echo -n > .temp

for x in `find .. -iname "*.java"`
do
	# Let's identify all the calls to getBaseL10n().getString("") and put them in a temp file
	# You might want to change this if you use another localization system.
	cat $x | grep "getBaseL10n().getString(\"" | sed -s "s/^.*getBaseL10n()\.getString(\"\(.\+\)\").*$/\1/" | sed -s "s/\".*$//" | uniq | sort >> .temp
done

echo -n > .temp2

for x in `cat .temp`
do
	# Let's see if all the entries in the temp files are contained in the l10n file specified as first argument
	# If an entry isn't contained, it's stored in another temporary file.
	CONTAINS=`cat $1 | sed -s "s/=.*//" | grep "^$x$"`
	if [ "`echo $CONTAINS`" = "`echo`" ]
	then
		echo $x >> .temp2
	fi	
done

# Show the temp file where all untranslated strings are stored.
cat .temp2 | sort | uniq 

rm -f .temp .temp2
