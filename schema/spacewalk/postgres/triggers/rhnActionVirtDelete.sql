-- oracle equivalent source sha1 8974449604ebcb9d66157870fb83c11521d45900
create or replace function rhn_avdl_mod_trig_fun() returns trigger as
$$
begin
	new.modified := current_timestamp;
	return new;
end;
$$ language plpgsql;

create trigger
rhn_avdl_mod_trig
before insert or update on rhnActionVirtDelete
for each row
execute procedure rhn_avdl_mod_trig_fun();

