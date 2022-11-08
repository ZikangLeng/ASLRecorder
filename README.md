# ASLRecorder (WIP) 

Android app for Deaf and hard-of-hearing (DHH) users to record training videos for
sign language recognition models. Forked from Sahir Shahryar's ASLRecorder: https://github.com/sahirshahryar/ASLRecorder


## Progress
- [x] Show camera feed
- [x] Save video recordings
- [x] Use round button instead of rectangular one
- [x] Show which word the user should sign at the top of the interface
  - [x] Automatically label recordings by the word shown at the top of the UI
- [x] Actual list of words!
- [x] Dynamic device compatibility (query device capabilities instead of assuming them)
  - [x] Fetch camera list
- [x] Check permissions and ask if not granted - **currently shows instructions but not prompt**
  - [ ] Don't require app to restart once permission has been granted
- [x] Test and tweak haptic feedback (**CAVEAT**: Works on Pixel 5a, but not Samsung Galaxy A12)
- [ ] UI Improvements
  - [x] Non-stretched camera preview
    - [x] Make it 16:9
  - [x] Timer
  - [x] Feedback on successful recording
    - [x] Haptics
    - [x] Visual feedback
  - [ ] Home page, tutorial?
  - [x] Recording sessions (esp. if we have a lot of one-handed signs)
    - [x] Random selection, topics, etc.?
    - [x] **Option to delete a botched recording** (better for us too!)
    - [x] Save all recordings to Google Photos on session end
  - [x] Intermediate screen to let users know when they will stop recording
  - [x] All-done screen once all needed phrases are recorded
- [x] Persistence? (Store which signs users have already recorded so they don't need to re-record)
- [x] Multitasking support (current version may crash on exit/reopen)
- [x] Easy access to video files (upload? direct file browser?) — **copy to Downloads folder**
  - [x] Upload strategy for videos (upload to Google Photos - preserves file name and uploads automatically), however there is a toggle that must be flipped on in the Google Photos Android
  app to enable automatic uploads. Should be done before shipping devices out.
- [x] Multiuser support (includes user information in saved files)
- [x] App asks for camera and storage permission before starting
    - [x] App handles camera and storage permission denial gracefully
- [x] Support Android 11+ external writing
- [x] General bugfixing (no crashes)


## Notes
* To run this app in the Android emulator, you may need to explicitly enable the
webcam. In Android Studio, go to `Tools` > `AVD Manager`, click the pencil
(edit) button for the emulator of your choice, press `Show Advanced Settings`,
and choose your webcam (likely `Webcam0`) under `Camera` > `Front`. Press
`Finish` to save.

* If you encounter issues where the entire Android emulator crashes when trying
to run the app, go to the same window as above, press `Show Advanced Settings`,
and set `Emulated Performance` > `Graphics` to `Software`.

## Words list
ASLRecorder uses the following 250 words:

```
after
airplane
all
alligator
another
ant
any
apple
arm
asleep
aunt
awake
bad
bath
bathroom
because
bed
bedroom
bee
before
beside
better
bird
black
blow
blue
book
boy
brown
bug
bye
call (on phone)
camera
can
candy
car
carrot
cat
cereal
chair
cheek
chicken
child
chin
clean
close
closet
cloud
clown
cow
cowboy
cry
cut
cute
dad
dance
dirty
dog
donkey
door
down
drawer
drink
dry
dryer
duck
ear
eat
elephant
empty
eye
face
fall
farm
find
fine
finger
finish
fireman
first
fish
flag
food
for
french fries
frog
garbage
giraffe
girl
give
glass (window)
go
good
goose
grandma
grandpa
grass
green
gum
hair
happy
hat
hate
have to
have
he, she, it
head
hear
helicopter
hello
hen
high
home
horse
hot
hungry
ice cream
ice
if
jacket
jump
keys
kick
kiss
kitty
lamp
later
light
like
lion
lips
listen
look
loud
mad
make
man
many
milk
mine, my
mitten
mom
moon
mouse
mouth
nap
napkin
naughty
night-night
night
no
noisy
nose
not
now
nuts
off
old
open
orange
out
outside
owie
owl
party
pen
pencil
penny
pick
pickle
pig
pizza
please
police
pool
potty
pretend
pretty
puppy
purse
puzzle
radio
rain
read
red
refrigerator
sad
same
sauce
say
scissors
see
shhh
shirt
shoe
shower
sleep
sleepy
snack
snow
soup
stairs
stay
sticky
store
stuck
sun
table
talk
taste
thank you
that
there
think
thirsty
this
tiger
tissue
tomorrow
tongue
tonight
tooth
toothbrush
touch
toy
tree
TV
uncle
underwear
up
vacuum
wake
watch
water
we, us
wet
where
white
who
why
will
wish
wolf
yellow
yes
yesterday
yourself
yucky
zebra
zipper
```