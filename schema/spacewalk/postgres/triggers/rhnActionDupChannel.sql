-- oracle equivalent source sha1 efdd82b0f3549ca45c05350b8582ecf47cda2876
create or replace function rhn_actiondupchan_mod_trig_fun() returns trigger as
$$
begin
	new.modified := current_timestamp;
	return new;
end;
$$ language plpgsql;

create trigger
rhn_actiondupchan_mod_trig
before insert or update on rhnActionDupChannel
for each row
execute procedure rhn_actiondupchan_mod_trig_fun();

