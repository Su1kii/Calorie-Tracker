CREATE TABLE food_items (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    barcode TEXT,
    calories_per_100g NUMERIC(10, 4) NOT NULL,
    protein_per_100g NUMERIC(10, 4) NOT NULL,
    carb_per_100g NUMERIC(10, 4) NOT NULL,
    fat_per_100g NUMERIC(10, 4) NOT NULL,
    fiber_per_100g NUMERIC(10, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_food_items_name ON food_items (name);
CREATE INDEX idx_food_items_name_trgm ON food_items USING GIN (name gin_trgm_ops);
CREATE UNIQUE INDEX idx_food_items_barcode ON food_items (barcode) WHERE barcode IS NOT NULL;