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


## Development environment

- Usage of the Eclipse IDE is recommended to keep the style coherent.  
  Eclipse project configuration files are shipped in the repository.  
  See [the README](/README.md) for how to use them.

- The most code quality improving features of Eclipse are the `Open Call Hierarchy (Ctrl + Alt + H)`
  and `References / Workspace (Shift + Ctrl + G)` features in the context menu.  
  To use them, select the name of a function / class / variable / etc., right click and choose the
  feature from the menu.  
  
  These features allow you to see where functions etc. are used/referenced in the codebase.  
  
  When refactoring, use them to make sure all relevant places are modified.  
  E.g. when you change the behavior of a function, review all its callers for whether they can
  cope with the new behavior, and all documentation which references it for whether it still is
  accurate.
  
  This is a lot more powerful than merely doing a text search, because e.g. it can also deal with
  things which have very short names such as `get()` for which a text search would yield a very
  large amount of false search results.  

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

- If a function definition or a function call is too long for a single line, the line it is wrapped
  into is indented with an additional tab.

- Line endings are `\n`. Some legacy files may use Windows' `\r\n` line endings instead. These shall
  be kept as is while the Eclipse IDE does not support mixed line endings.  
  Once it supports that you can gradually change them to `\n` as part of doing other non-whitespace
  changes.

- Multiple nested synchronized() blocks are formatted / indented as such:
  ```java
  synchronized(A) { // Comment on what needs the lock A
  synchronized(B) { // Comment on what needs the lock B
  synchronized(C) { // Comment on what needs the lock C
  	code();
  }}}
  ```

- Ordering of functions inside classes: Put high-level functions first, then after the highest-level
  functions put the lower-level functions which the high-level functions use, and at the end put
  very low-level helper functions.  
  E.g.:
  ```java
  class PizzaProducer {
  	public void makePizza() {     // Highest level
  		makeRawPizza();
  		bakePizza();
  	}
  
  	private void makeRawPizza() {  // Medium level
  		makeDough();
  		putOnToppings();
  	}
  
  	private void makeDough()     { /* ... */ }  // Low level
  	private void putOnToppings() { /* ... */ }
  	private void bakePizza()     { /* ... */ }
  }
  ```
  This order ensures that when someone reads the class top to bottom then they will quickly get
  a rough overview of what it does before they get lost in technical details.

## Variables

- Member variable names are prefixed with "m", unless the variables are static.  
  The first letters of *all* following words are written in uppercase.  
  Example: `private int mSomeVariableName;`

- Names of static member variables are written all uppercase, with words separated by "_".  
  Example: `static int STATIC_VARIABLE_NAME = 1;`

- Keywords which modify variables are always sorted in the same order, which is specified by the
  following sample.  
  Example: `public static transient final int SOME_CONSTANT = 1;`  

## Functions

- Keywords which modify functions are always sorted in the same order, which is specified by the
  following samples.  
  Example: `public static final int function() {}`  
  Example: `public static final <T> ArrayList<T> function(Set<T> input) {}`

## Statements

- `if(...)`, `while(...)`, `catch(...)`, `functionCall(...)` etc. are written without whitespace
  before the parentheses.  
  Curly brackets which may follow are added to the same line and separated with a single space.  
  Example: `if(...) {`

- Assertions are written as `assert(BOOLEAN)` instead of `assert BOOLEAN` even though Java allows
  the latter.  
  This is to match e.g. `if(BOOLEAN) ...` also requiring parentheses.  
  Notice that Java assertions also support adding a value as documentation which will be converted
  to String and used as message of the AssertionError exception which is thrown in case the
  assertion fails.  
  Example: `assert(BOOLEAN) : VALUE;`

- The `catch(...) {` of a try-catch block is written on the same line as the `}` which ends the
  try-block. Same applies to the `finally { ... }`.  
  Example:
  ```java
  try {
  	stuff();
  } catch(Exception e) {
  	repair();
  } finally {
  	alwaysDo();
  }
  ```

- When checking a variable `actual` for whether it is of the correct value EXPECTED_CONSTANT (e.g.
  a hardcoded String), do **not** use the shortcut of `if(!EXPECTED_CONSTANT.equals(actual)) {...}`
  to avoid a null check.  
  Instead, use `if(actual != null && !actual.equals(EXPECTED_CONSTANT)) {...}`.
  
  The additional null-check which this may require is accepted as a valid tradeoff in favor of
  avoiding very unreadable code:  
  The human train of thought is "is the variable equal to what it should be?", not "is what it
  should be equal to what the variable is?".  
  The code should match the human train of thought for readability.
  
  If a variable ought not be null, you should use the following to ensure an exception is thrown if
  it is null:
  ```java
  requireNonNull(actual); // Add a static import from class Objects to obtain this function.
  
  if(!actual.equals(EXPECTED)) {
  	// Deal with wrong value
  }
  ```

## Terminology

- Freenet in general uses the word "fetch" when something is downloaded from Freenet, and "insert"
  when something is uploaded to Freenet.  
  This also applies to older parts of the WoT/FT source code.  
  Since these words are never used for that purpose in other non-Freenet software, WoT and FT are
  gradually being migrated to use the words "download" / "upload" instead.  
  Thus please use them in newly written code.  
  Existing code can be gradually changed to use them as it is modified for other reasons anyway.

## Documentation

- Stuff which **MUST** be fixed before a release (or at least reviewed for whether postponing it
  until the next release is OK) is marked and explained with `FIXME: ...` in the code if that is
  easier than filing a bugtracker entry which explains it.  
  Stuff which needs not be fixed before a release but would be good to fix someday is documented
  with `TODO: ...`.
  
  FIXMEs and especially TODOs (due to their long-term nature) should if possible be prefixed with
  `Bug:`, `Code quality:`, `Performance:`, `Usability:` such as e.g. `FIXME: Bug: ...` to ease
  locating work which affects a particular one of those categories.  
  Multiple such prefixes may be concatenated.
  
  Often TODOs and FIXMEs evolve. As their final goal is to have them removed, it is then usually
  not worth the effort to rewrite their whole text body to adapt it to changes.  
  Thus, when updating them, you may just leave the outdated part as-is and append the update to the
  end in a new paragraph which starts with `EDIT: ...`.  
  The `EDIT:` prefix makes it clear why the original comment might be in contradiction with the
  added text.

- WoT's source code contains very much documentation. That shall stay as is.  
  Hence please do not commit undocumented code.  
  You should not add trivial documentation such as documenting getters which return trivial
  values!  
  But anything which is not obvious should be documented.

- WoT's JavaDoc documentation uses Markdown syntax instead of HTML so it is more convenient to read
  while reading the source code.  
  (There currently is no tooling in place to render JavaDoc HTML files from that, and it does not
  matter if tools for that even exist yet:  
  During lots of WoT development I noticed that using Eclipse to navigate to the relevant JavaDoc
  code in the Java source code is fast enough, there is no need to read the JavaDoc externally using
  HTML files.)
  
  Some places may still be using HTML syntax instead of Markdown.  
  These shall be gradually migrated to Markdown once they're modified anyway for other reasons.

- JavaDoc fields to avoid:
  - @author: Use `git blame` instead.  
    Legacy instances of this field may still be present in the codebase. They can be removed.

- To not waste space, the lines containing `/**` to start a JavaDoc section and `*/` to end it are
  also used for containing the JavaDoc text body.  
  A single space separates the `/**` and `*/` from the text.  
  Lines in between start with ` *` and they have two spaces after the `*` to align the text to
  the `/**` line.  
  It is also allowed and recommended to only use a single line for a whole JavaDoc section if it
  fits.  
  Examples:
  ```java
  /** Short JavaDoc. */
  int mSomeVariable;
  
  /** First line.  
   *  Second line.  
   *  Third line. */
  int getStuff();
  ```

- Important comments are prefixed with `WARNING:`, `ATTENTION:`, or `NOTICE:`.  
  The first two are very important comments, the last one is a bit less important.
  
  In JavaDoc, these may be surrounded with Markdown's `**` to make them bold, e.g. `**NOTICE:**`.  
  However most of the JavaDoc is not like that yet.  
  Existing instances shall be gradually converted as the code is modified for other reasons anyway.

- If class names are used in documentation, their spelling and capitalization shall not be modified
  in ways which would be meaning to take account for how it is typically done due to regular
  English spelling / grammar.  
  E.g. when talking about class `Identity`, you do not write `identity` or `identities` but
  `Identity` and `Identitys` in the documentation.

- When adding temporary measures to the code which can be removed once external libraries/services
  fix the underlying issue document this with something similar to:  
  `TODO: Code quality: As of YYYY-MM-DD this does not work due to bugs at <library and its version>.
  Please try if a more recent version fixes this, and remove the workaround if yes.`  
  Providing a date allows future developers to not have to dig into the Git history to decide if
  enough time has elapsed that a test to see if the workaround can be removed is worth the effort.

## Git

- **Git history is considered as part of the documentation of the codebase and therefore shall
  *NOT* be squashed / modified / deleted / tampered with!**
  
  Searching the history has aided fixing many bugs, it is unwise to tamper with it in any way!
  
  This especially means that you should **not** squash commits before merging a branch!
  
  To make the history more readable instead of squashing you should create sub-branches of your
  feature branch and merge them into the feature branch one after another.  
  Use `git merge --no-ff SUB_BRANCH` to ensure a merge commit is always created and use the merge
  commit message to summarize the sub-branch.  
  Then the merge commits serve as a replacement for what would otherwise be squashed commits.

- All commits should be gpg-signed.  
  Use `git config --global commit.gpgsign true` to enable this for all repositories, remove the
  `--global` to enable it for the current one only.

- The size of commits should be "atomic", i.e. a single commit should consist of a single step of
  work.  
  Do **not** create commits which span multiple hundred lines!  
  Large pieces of work can typically be broken down into multiple steps and thus commits to make
  each easy to understand *and* review.

- Commit messages however should not be too short, do explain very clearly what the commit aims to
  do and especially **why** it does that.
  
  The "why" is very important for future developers to be able to understand and debug the code!

- Commit messages have a line length limit of 72 characters.

- When indenting things in commit messages use 2 spaces.

- Commit messages start with a line `AREA_OF_CHANGES: SUMMARY_OF_CHANGES`.  
  The summary may be wrapped into multiple lines.  
  The first lines of the commit are the "subject" of the commit, like e.g. the subject of an
  email.  
  A blank line follows afterwards.  
  After the blank line the description of the commit follows.

  `AREA_OF_CHANGES: SUB_AREA_OF_CHANGES: SUMMARY_OF_CHANGES` is also valid.

  Examples:
  - `GitHub Actions: Speed up runtime by ~ 1 minute`
  - `GitHub Actions: MacOS: Fix syntax error in shell script`

- Common values of AREA_OF_CHANGES:
  - `CLASS`, a class name.
  - `CLASS.functionName()`
  - `SUBSYSTEM`, e.g. "GitHub Actions".
  - `FILENAME`, e.g. name of a shell script.

- Common commit subjects, where AREA is short for AREA_OF_CHANGES:
  - `Add class CLASS`
  - `CLASS: Add function()`
  - `CLASS: Implement function()`
  - `UNIT_TEST_CLASS: Add test for function()`
  - `AREA: Amend the previous commit`.  
    **NOTICE:** Use this instead of `git commit --amend ; git push --force`!  
    I.e. never change a pushed commit, instead create an additional one to fix the bogus commit.  
    (This is because git history should never be modified.)
  - `AREA: Documentation`
  - `AREA: Documentation: What it is about`
  - `AREA: Document bug`
  - `AREA: Document potential bug`
  - `AREA: Clarify documentation`
  - `AREA: Defensive programming`, when making code paranoidly safe against unlikely breakage.
  - `AREA: Improve readability`
  - `AREA: Wire-in THING` to state that THING is being connected to AREA to make it execute.
  - `AREA: Indentation` - Committing indentation separately of other code makes it easy to review.
  - `AREA: Whitespace` - for non-indentation whitespace changes. See above.
  - `AREA: Recycle resolved FIXME/TODO into documentation`
  - `AREA: Reduce LOC` - when simplifying things.
  - `AREA: Obey line length limit`
  - `Fix typo`

- Merge commits start with `Merge branch 'SUB_BRANCH' into TARGET_BRANCH`.  
  TARGET_BRANCH must *not* be omitted:  
  Git usually does this in the default message when you merge into branch `master`.  
  Add it manually then.

- The second line of the merge commit message shall be `= DESCRIPTION`, including the `= `,
  where the description summarizes the work of the branch.  
  It shall be the same as the title of the related pull request.

- When submitting code via a pull request to the upstream repository `hyphanet/plugin-WebOfTrust`
  it shall **include** a merge commit which merges the PR's branch into the target branch.  
  I.e. the developer of a branch writes the merge commit already, not the maintainer who merges it
  into the repository.  
  Because the developers of branches write the merge commit messages themselves it is ensured
  that they have the highest possible quality - nobody understands code better than the person who
  wrote it.  
  Further, merge conflicts are thereby resolved by someone who understands the code they have just
  modified, which guarantees the conflicts are resolved in a fashion which is coherent with the
  modifications.
  
  To always create a merge commit when merging branches, use `git merge --no-ff BRANCH`.  
  The reviewer then merges it with `git merge --ff-only ...` in order to **not** create a merge
  commit of their own: Duplicate merge commits would make the git history very unreadable.  
  To signal to the reviewer that this approach has been followed, include the following message
  in the PR: `IMPORTANT: As requested by the style guideline, this already includes a merge commit!
  Thus please merge with '--ff-only' into BRANCH_NAME to not create a nested duplicate merge
  commit!`
