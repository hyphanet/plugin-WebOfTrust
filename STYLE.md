FIXME: I've been obeying a consistent style when writing WoT/FT code for many years.  
Write it down here.
# Web of Trust / Freetalk code style guideline

WoT **thoroughly** follows this style guideline, please keep obeying it:

The guideline has been written by [xor-freenet](https://github.com/xor-freenet) *after* a decade of
his work on the WoT codebase to accurately describe the style he had been following.

Hence WoT is *not* in the typical unfortunate situation of picking an arbitrary style guideline off
the Internet after having tons of code at hand already and then trying to force the codebase into it
while it violates the guideline in most places.  
Instead, *the guideline* was forced to reflect the codebase's as-is situation and thus it hopefully
should already be obeyed in most places.

Therefore xor-freenet hereby kindly requests that this guideline will be kept as-is in perpetuity
and followed in perpetuity to keep the codebase maximally coherent.

Freetalk, where xor-freenet also wrote most of the code, shall use this guideline of WoT, too, to
avoid having to duplicate it in Freetalk's repository.

## Git history

**Git history is considered as part of the documentation of the codebase and therefore shall
*NOT* be squashed / modified / deleted / tampered with!**

Searching the history has aided fixing many bugs, it is unwise to tamper with it in any way!

This especially means that you should **not** squash commits before merging a branch!

To make the history more readable instead of squashing you should create sub-branches of your
feature branch and merge them into the feature branch one after another.  
Use `git merge --no-ff SUB_BRANCH` to ensure a merge commit is always created and use the merge
commit message to summarize the sub-branch.  
Then the merge commits serve as a replacement for what would otherwise be squashed commits.

For further details on Git usage see the [Git](#git) section below.  
(This part here is a separate section to stress its importance.)

## Git
