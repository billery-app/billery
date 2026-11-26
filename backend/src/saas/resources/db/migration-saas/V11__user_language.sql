-- The language of the user interface per user (#15): 'de' or 'en', NULL while none was chosen
-- (the app shows German). A preference of the person, not of a company, so it lives in app_user.
ALTER TABLE app_user ADD COLUMN ui_language VARCHAR(5)
    CHECK (ui_language IN ('de', 'en'));
