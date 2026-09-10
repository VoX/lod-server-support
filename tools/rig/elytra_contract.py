"""Canonical native commands and independent phase state contract."""
PHASES=('equipped','crouched','standing_recovered','falling','gliding','landed')
SUBJECT='ElytraSubject';OBSERVER='Voximus_Maximus'
def predicates(phase,run_id):
 ground=phase not in ('falling','gliding');flying=phase=='gliding'
 prefix='LSS_ELYTRA_'+run_id+'_'+phase+'_'
 return {
  'survival':('execute if entity '+SUBJECT+'[gamemode=survival] run say '+prefix+'SURVIVAL',prefix+'SURVIVAL'),
  'equipment':('execute if items entity '+SUBJECT+' armor.chest minecraft:elytra run say '+prefix+'EQUIPMENT',prefix+'EQUIPMENT'),
  'native_pose':('execute if entity '+SUBJECT+'[nbt={OnGround:'+('1b' if ground else '0b')+',FallFlying:'+('1b' if flying else '0b')+'}] run say '+prefix+'POSE',prefix+'POSE')}
def setup_commands():
 return ['time set day','weather clear','gamemode survival '+SUBJECT,'gamemode creative '+OBSERVER,'forceload add -96 80 96 240','setblock 0 119 0 minecraft:stone','fill -4 80 156 4 80 164 minecraft:stone','fill -96 40 80 96 40 240 minecraft:stone','tp '+OBSERVER+' 0.5 120 0.5 0 15','tp '+SUBJECT+' 0 81 160 90 10','item replace entity '+SUBJECT+' armor.chest with minecraft:elytra','effect give '+SUBJECT+' minecraft:resistance 180 4 true']
def falling_commands():return ['tp '+OBSERVER+' 0.5 120 0.5 0 -15','tp '+SUBJECT+' 0 180 160 90 10']
def landing_command():return 'tp '+OBSERVER+' 0.5 120 0.5 0 25'
