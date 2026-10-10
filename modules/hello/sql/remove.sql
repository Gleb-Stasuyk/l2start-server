-- Manual removal of module "hello" data (not run automatically).
DROP TABLE IF EXISTS mod_hello_counter;
DELETE FROM platform_module_migrations WHERE module_id = 'hello';
DELETE FROM platform_modules WHERE module_id = 'hello';
