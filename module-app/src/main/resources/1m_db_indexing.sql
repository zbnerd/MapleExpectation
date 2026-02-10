SELECT SQL_NO_CACHE * FROM item_equipment
WHERE star_force = 22
  AND potential_grade = 'LEGENDARY'
  AND part = 'Gloves';

CREATE INDEX idx_search_test ON item_equipment (star_force, potential_grade, part);