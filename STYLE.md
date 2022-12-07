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

Freetalk, where xor-freenet also wrote most of the code and thus the same style was followed, shall
use this guideline of WoT, too, to avoid having to duplicate it in Freetalk's repository.

## General conduct

In general, WoT follows a "engineering first" style fashion.  
This means that bikeshedding is discouraged in favor of spending more time actually writing code.  
In practice, it translates to:  
Engineers are trusted to make wise decisions behind how they format their code.  
Thus the below style guidelines may be violated in reasonable exceptions where it makes sense in
order to improve the readability of the code.  

E.g.:
```java
String a = "long_prefix" + "infix" + "long_suffix";
String b =      "prefix" + "infix" +      "suffix";
```

Here excess whitespace was manually inserted to align the strings with each other across multiple
lines in order to stress their meaning, i.e. the common parts among them.  
This cannot be done by strict rules because it depends on the semantics of the code, not on the
mere syntax.

As a consequence of this, the **usage of automated code formatters on pre-existing code is
prohibited** because it would destroy the above cases of well-chosen manual formatting.

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

## Layout

- Line length limit is 100 characters.
  
  The l10n files are an exception, there each `key=value` pair is on a single line, no matter how
  long the line will be.
  
  The 100 char limit was introduced after a lot of WoT code was already written, hence it is violated
  in some places for no good reason.  
  These violations shall be fixed gradually once the affected code is modified for other reasons
  anyway.

- Indentation of the Java part of the codebase is done with tabs.  
  Configure your editor to use a tab width of 4 spaces to ensure the line length limit is obeyed.
  
  Some few places in the existing code may use spaces instead for legacy reasons.  
  These violations shall be fixed gradually once the affected code is modified for other reasons
  anyway, with 4 spaces = 1 tab.
  
  Spaces may be added to lines which are indented with tabs in order to align them to relevant code
  at the above line.  
  I.e. first use tabs for base alignment and then add spaces after the tabs for fine alignment.  
  Make sure to start the indentation with the same count of tabs as the above line contains so that
  the lines will align properly independent of a different tab-width which other developers may have
  chosen in their IDE.

- The GitHub Actions `.yml` files are indented with 2 spaces and shall stay like that.

- Empty lines inside of an indented block are indented to match the surrounding indentation. E.g:
  ```java
  void function() {
  	a();
  	
  	b();
  }
  ```
  The line between `a();` and `b();` is indented to the same level as those two calls.

- Excess whitespace may be introduced inside of statements to make their meaning more apparent,
  see [General conduct](#general-conduct) for an example.  
  To ensure this manually chosen indentation is not destroyed, the **usage of automated code
  formatters upon pre-existing code is disallowed**.  
  In practice this means: Use your IDE to format code as you write the code and thus immediately
  review the formatting - but refrain from auto-formatting code afterwards for the mere sake of
  auto-formatting code.

## Git
