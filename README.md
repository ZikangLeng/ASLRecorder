# ASLRecorder (WIP) 

Android app for Deaf and hard-of-hearing (DHH) users to record training videos for
sign language recognition models. Forked from Sahir Shahryar's ASLRecorder: 
https://github.com/sahirshahryar/ASLRecorder


## Releases
### 1.1 (WIP)
- [ ] Code cleanup
- [x] Show maximal field of view for camera
- [x] Include DPAN recordings for all words
- [x] Remove passwords from source code
- [ ] Bug fixes
  - [x] Crashes on older versions of Android when sending mail
  - [x] `IllegalStateException`s when closing recording activity

### 1.0
* Initial release


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
animal
another
any
apple
arm
aunt
awake
backyard
bad
balloon
bath
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
boat
book
boy
brother
brown
bug
bye
call (on phone)
can
car
carrot
cat
cereal
chair
cheek
child
chin
chocolate
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
doll
donkey
down
drawer
drink
drop
dry
dryer
duck
ear
elephant
empty
every
eye
face
fall
farm
fast
feet
find
fine
finger
finish
fireman
first
fish
flag
flower
food
for
french fries
frog
garbage
gift
giraffe
girl
give
glass (window)
go
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
have
have to
he, she, it
head
hear
helicopter
hello
hen
hide
high
home
horse
hot
hungry
ice cream
if
into
jacket
jeans
jump
kiss
kitty
lamp
later
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
morning
mouse
mouth
nap
napkin
night
no
noisy
nose
not
now
nuts
old
on
open
orange
outside
owie
owl
pajamas
pen
pencil
penny
person
pig
pizza
please
police
pool
potty
pretend
pretty
puppy
puzzle
quiet
radio
rain
read
red
refrigerator
ride
room
sad
same
say
scissors
see
shhh
shirt
shoe
shower
sick
sleep
sleepy
smile
snack
snow
stairs
stay
sticky
store
story
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
tiger
time
tomorrow
tongue
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
wait
wake
water
we, us
wet
where
white
who
why
will
wolf
yellow
yes
yesterday
yourself
yucky
zebra
zipper
```