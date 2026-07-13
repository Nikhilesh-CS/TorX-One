# Contributing to TorX One

First off, thank you for considering contributing to TorX One! It's people like you that make TorX One such a great tool.

## Where do I go from here?

If you've noticed a bug or have a feature request, make sure to check our [Issues](../../issues) to see if someone else has already created a ticket. If not, go ahead and make one!

## Fork & create a branch

If this is something you think you can fix, then fork TorX One and create a branch with a descriptive name.

A good branch name would be (where issue #325 is the ticket you're working on):

```sh
git checkout -b 325-add-dark-mode
```

## Get the test suite running

Make sure the app compiles and you've tested your changes on a physical device or emulator. Since this project uses native Tor binaries, testing on physical devices (arm64-v8a) is highly recommended.

## Implement your fix or feature

At this point, you're ready to make your changes. Feel free to ask for help! 

## Make a Pull Request

At this point, you should switch back to your master branch and make sure it's up to date with TorX One's master branch:

```sh
git remote add upstream https://github.com/Nikhilesh-CS/TorX-One.git
git checkout main
git pull upstream main
```

Then update your feature branch from your local copy of master, and push it!

```sh
git checkout 325-add-dark-mode
git rebase main
git push --set-upstream origin 325-add-dark-mode
```

Finally, go to GitHub and make a Pull Request.

## Keeping your Pull Request updated

If a maintainer asks you to "rebase" your PR, they're saying that a lot of code has changed, and that you need to update your branch so it's easier to merge.
