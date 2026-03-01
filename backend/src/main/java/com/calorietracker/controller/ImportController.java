package com.calorietracker.controller;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.service.ImportJobService;
import com.calorietracker.service.DatasetCorrectionService;
import com.calorietracker.service.DummyJsonRecipeImportService;
import com.calorietracker.service.OpenFoodFactsImportService;
import com.calorietracker.service.SweetsDatasetImportService;
import com.calorietracker.service.TheMealDbImportService;
import com.calorietracker.service.WikimediaImageImportService;

import static org.springframework.http.HttpStatus.CONFLICT;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private static final String DEFAULT_CUISINES =
        "indian,chinese,indo chinese,european,mediterranean,african,western,eastern,northern,southern";
    private static final String DEFAULT_GLOBAL_COUNTRIES =
        "india,china,japan,thailand,vietnam,indonesia,philippines,saudi-arabia,turkey,egypt,morocco,nigeria,south-africa,"
            + "united-states,canada,mexico,brazil,argentina,chile,peru,italy,spain,france,germany,united-kingdom,portugal,poland,russia,ukraine,greece,australia";
    private static final String DEFAULT_DESSERT_COUNTRIES =
        "india,china,japan,south-korea,thailand,vietnam,indonesia,philippines,italy,france,germany,spain,greece,turkey,"
            + "united-kingdom,united-states,mexico,brazil,argentina,south-africa,nigeria,australia";

    private final OpenFoodFactsImportService openFoodFactsImportService;
    private final TheMealDbImportService theMealDbImportService;
    private final DummyJsonRecipeImportService dummyJsonRecipeImportService;
    private final WikimediaImageImportService wikimediaImageImportService;
    private final SweetsDatasetImportService sweetsDatasetImportService;
    private final DatasetCorrectionService datasetCorrectionService;
    private final ImportJobService importJobService;
    private final AtomicBoolean importInProgress = new AtomicBoolean(false);

    public ImportController(
        OpenFoodFactsImportService openFoodFactsImportService,
        TheMealDbImportService theMealDbImportService,
        DummyJsonRecipeImportService dummyJsonRecipeImportService,
        WikimediaImageImportService wikimediaImageImportService,
        SweetsDatasetImportService sweetsDatasetImportService,
        DatasetCorrectionService datasetCorrectionService,
        ImportJobService importJobService
    ) {
        this.openFoodFactsImportService = openFoodFactsImportService;
        this.theMealDbImportService = theMealDbImportService;
        this.dummyJsonRecipeImportService = dummyJsonRecipeImportService;
        this.wikimediaImageImportService = wikimediaImageImportService;
        this.sweetsDatasetImportService = sweetsDatasetImportService;
        this.datasetCorrectionService = datasetCorrectionService;
        this.importJobService = importJobService;
    }

    @PostMapping("/open-food-facts")
    public Map<String, Object> importOpenFoodFacts(
        @RequestParam(defaultValue = "united-states,india,japan,mexico,italy,brazil") String countries,
        @RequestParam(defaultValue = "2") Integer pages,
        @RequestParam(defaultValue = "120") Integer pageSize
    ) {
        return runImportExclusively(() -> buildOpenFoodFactsImport(countries, pages, pageSize));
    }

    @PostMapping("/open-food-facts/async")
    public Map<String, Object> importOpenFoodFactsAsync(
        @RequestParam(defaultValue = "united-states,india,japan,mexico,italy,brazil") String countries,
        @RequestParam(defaultValue = "2") Integer pages,
        @RequestParam(defaultValue = "120") Integer pageSize
    ) {
        String jobId = importJobService.submit(
            "OPEN_FOOD_FACTS",
            () -> runImportExclusively(() -> buildOpenFoodFactsImport(countries, pages, pageSize))
        );

        return Map.of(
            "status", "accepted",
            "jobId", jobId,
            "poll", "/api/import/jobs/" + jobId
        );
    }

    @PostMapping("/world-cuisines")
    public Map<String, Object> importWorldCuisines(
        @RequestParam(defaultValue = DEFAULT_CUISINES) String cuisines,
        @RequestParam(defaultValue = "40") Integer maxPerCuisine,
        @RequestParam(defaultValue = "true") Boolean includeOpenFoodFacts,
        @RequestParam(defaultValue = "india,china,japan,italy,greece,morocco,united-states") String countries,
        @RequestParam(defaultValue = "3") Integer pages,
        @RequestParam(defaultValue = "150") Integer pageSize
    ) {
        return runImportExclusively(() -> buildWorldCuisineImport(cuisines, maxPerCuisine, includeOpenFoodFacts, countries, pages, pageSize));
    }

    @PostMapping("/world-cuisines/async")
    public Map<String, Object> importWorldCuisinesAsync(
        @RequestParam(defaultValue = DEFAULT_CUISINES) String cuisines,
        @RequestParam(defaultValue = "40") Integer maxPerCuisine,
        @RequestParam(defaultValue = "true") Boolean includeOpenFoodFacts,
        @RequestParam(defaultValue = "india,china,japan,italy,greece,morocco,united-states") String countries,
        @RequestParam(defaultValue = "3") Integer pages,
        @RequestParam(defaultValue = "150") Integer pageSize
    ) {
        String jobId = importJobService.submit(
            "WORLD_CUISINES",
            () -> runImportExclusively(() -> buildWorldCuisineImport(
                cuisines,
                maxPerCuisine,
                includeOpenFoodFacts,
                countries,
                pages,
                pageSize
            ))
        );

        return Map.of(
            "status", "accepted",
            "jobId", jobId,
            "poll", "/api/import/jobs/" + jobId
        );
    }

    @PostMapping("/global-datasets")
    public Map<String, Object> importGlobalDatasets(
        @RequestParam(defaultValue = DEFAULT_CUISINES) String cuisines,
        @RequestParam(defaultValue = "80") Integer maxPerCuisine,
        @RequestParam(defaultValue = "true") Boolean includeMealDbAreas,
        @RequestParam(defaultValue = "18") Integer maxPerArea,
        @RequestParam(defaultValue = "true") Boolean includeOpenFoodFacts,
        @RequestParam(defaultValue = DEFAULT_GLOBAL_COUNTRIES) String countries,
        @RequestParam(defaultValue = "3") Integer pages,
        @RequestParam(defaultValue = "180") Integer pageSize,
        @RequestParam(defaultValue = "true") Boolean includeDummyJson,
        @RequestParam(defaultValue = "50") Integer dummyJsonPageSize,
        @RequestParam(defaultValue = "300") Integer dummyJsonMaxRecipes
    ) {
        return runImportExclusively(
            () -> buildGlobalDatasetImport(
                cuisines,
                maxPerCuisine,
                includeMealDbAreas,
                maxPerArea,
                includeOpenFoodFacts,
                countries,
                pages,
                pageSize,
                includeDummyJson,
                dummyJsonPageSize,
                dummyJsonMaxRecipes
            )
        );
    }

    @PostMapping("/global-datasets/async")
    public Map<String, Object> importGlobalDatasetsAsync(
        @RequestParam(defaultValue = DEFAULT_CUISINES) String cuisines,
        @RequestParam(defaultValue = "80") Integer maxPerCuisine,
        @RequestParam(defaultValue = "true") Boolean includeMealDbAreas,
        @RequestParam(defaultValue = "18") Integer maxPerArea,
        @RequestParam(defaultValue = "true") Boolean includeOpenFoodFacts,
        @RequestParam(defaultValue = DEFAULT_GLOBAL_COUNTRIES) String countries,
        @RequestParam(defaultValue = "3") Integer pages,
        @RequestParam(defaultValue = "180") Integer pageSize,
        @RequestParam(defaultValue = "true") Boolean includeDummyJson,
        @RequestParam(defaultValue = "50") Integer dummyJsonPageSize,
        @RequestParam(defaultValue = "300") Integer dummyJsonMaxRecipes
    ) {
        String jobId = importJobService.submit(
            "GLOBAL_DATASETS",
            () -> runImportExclusively(
                () -> buildGlobalDatasetImport(
                    cuisines,
                    maxPerCuisine,
                    includeMealDbAreas,
                    maxPerArea,
                    includeOpenFoodFacts,
                    countries,
                    pages,
                    pageSize,
                    includeDummyJson,
                    dummyJsonPageSize,
                    dummyJsonMaxRecipes
                )
            )
        );

        return Map.of(
            "status", "accepted",
            "jobId", jobId,
            "poll", "/api/import/jobs/" + jobId
        );
    }

    @PostMapping("/images")
    public Map<String, Object> importImages(
        @RequestParam(defaultValue = "true") Boolean includeIngredients,
        @RequestParam(defaultValue = "true") Boolean includeDishes,
        @RequestParam(defaultValue = "1200") Integer ingredientLimit,
        @RequestParam(defaultValue = "1200") Integer dishLimit,
        @RequestParam(defaultValue = "true") Boolean overwriteExisting
    ) {
        return runImportExclusively(
            () -> buildImageImport(
                includeIngredients,
                includeDishes,
                ingredientLimit,
                dishLimit,
                overwriteExisting
            )
        );
    }

    @PostMapping("/images/async")
    public Map<String, Object> importImagesAsync(
        @RequestParam(defaultValue = "true") Boolean includeIngredients,
        @RequestParam(defaultValue = "true") Boolean includeDishes,
        @RequestParam(defaultValue = "1200") Integer ingredientLimit,
        @RequestParam(defaultValue = "1200") Integer dishLimit,
        @RequestParam(defaultValue = "true") Boolean overwriteExisting
    ) {
        String jobId = importJobService.submit(
            "WIKIMEDIA_IMAGES",
            () -> runImportExclusively(
                () -> buildImageImport(
                    includeIngredients,
                    includeDishes,
                    ingredientLimit,
                    dishLimit,
                    overwriteExisting
                )
            )
        );

        return Map.of(
            "status", "accepted",
            "jobId", jobId,
            "poll", "/api/import/jobs/" + jobId
        );
    }

    @PostMapping("/sweets-desserts")
    public Map<String, Object> importSweetsDesserts(
        @RequestParam(defaultValue = DEFAULT_DESSERT_COUNTRIES) String countries,
        @RequestParam(defaultValue = "1") Integer pages,
        @RequestParam(defaultValue = "120") Integer pageSize,
        @RequestParam(defaultValue = "14") Integer maxPerQuery,
        @RequestParam(defaultValue = "140") Integer maxMealDbDesserts,
        @RequestParam(defaultValue = "true") Boolean includeCuratedFallback
    ) {
        return runImportExclusively(
            () -> buildSweetsDessertImport(
                countries,
                pages,
                pageSize,
                maxPerQuery,
                maxMealDbDesserts,
                includeCuratedFallback
            )
        );
    }

    @PostMapping("/sweets-desserts/async")
    public Map<String, Object> importSweetsDessertsAsync(
        @RequestParam(defaultValue = DEFAULT_DESSERT_COUNTRIES) String countries,
        @RequestParam(defaultValue = "1") Integer pages,
        @RequestParam(defaultValue = "120") Integer pageSize,
        @RequestParam(defaultValue = "14") Integer maxPerQuery,
        @RequestParam(defaultValue = "140") Integer maxMealDbDesserts,
        @RequestParam(defaultValue = "true") Boolean includeCuratedFallback
    ) {
        String jobId = importJobService.submit(
            "SWEETS_DATASETS",
            () -> runImportExclusively(
                () -> buildSweetsDessertImport(
                    countries,
                    pages,
                    pageSize,
                    maxPerQuery,
                    maxMealDbDesserts,
                    includeCuratedFallback
                )
            )
        );

        return Map.of(
            "status", "accepted",
            "jobId", jobId,
            "poll", "/api/import/jobs/" + jobId
        );
    }

    @PostMapping("/correct-datasets")
    public Map<String, Object> correctDatasets(
        @RequestParam(defaultValue = "true") Boolean promoteFactChecked
    ) {
        return runImportExclusively(
            () -> buildDatasetCorrection(promoteFactChecked)
        );
    }

    @PostMapping("/correct-datasets/async")
    public Map<String, Object> correctDatasetsAsync(
        @RequestParam(defaultValue = "true") Boolean promoteFactChecked
    ) {
        String jobId = importJobService.submit(
            "DATASET_CORRECTION",
            () -> runImportExclusively(
                () -> buildDatasetCorrection(promoteFactChecked)
            )
        );

        return Map.of(
            "status", "accepted",
            "jobId", jobId,
            "poll", "/api/import/jobs/" + jobId
        );
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> getJobStatus(@PathVariable String jobId) {
        return importJobService.getJobOrThrow(jobId);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs(@RequestParam(defaultValue = "20") Integer limit) {
        return importJobService.listJobs(limit);
    }

    private Map<String, Object> buildOpenFoodFactsImport(String countries, Integer pages, Integer pageSize) {
        int imported = openFoodFactsImportService.importFoods(parseCsv(countries), pages, pageSize);
        return Map.of(
            "status", "ok",
            "imported", imported,
            "source", "OpenFoodFacts"
        );
    }

    private Map<String, Object> buildWorldCuisineImport(
        String cuisines,
        Integer maxPerCuisine,
        Boolean includeOpenFoodFacts,
        String countries,
        Integer pages,
        Integer pageSize
    ) {
        List<String> cuisineList = parseCsv(cuisines);
        Map<String, Integer> cuisineImport = theMealDbImportService.importByCuisineLabels(cuisineList, maxPerCuisine);

        int importedFoods = 0;
        if (Boolean.TRUE.equals(includeOpenFoodFacts)) {
            importedFoods = openFoodFactsImportService.importFoods(parseCsv(countries), pages, pageSize);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("source", "TheMealDB + OpenFoodFacts");
        response.put("dishes", cuisineImport);
        response.put("ingredientsFromOpenFoodFacts", importedFoods);
        return response;
    }

    private Map<String, Object> buildGlobalDatasetImport(
        String cuisines,
        Integer maxPerCuisine,
        Boolean includeMealDbAreas,
        Integer maxPerArea,
        Boolean includeOpenFoodFacts,
        String countries,
        Integer pages,
        Integer pageSize,
        Boolean includeDummyJson,
        Integer dummyJsonPageSize,
        Integer dummyJsonMaxRecipes
    ) {
        List<String> cuisineList = parseCsv(cuisines);

        Map<String, Integer> cuisineImport = theMealDbImportService.importByCuisineLabels(cuisineList, maxPerCuisine);
        Map<String, Integer> areaImport = Boolean.TRUE.equals(includeMealDbAreas)
            ? theMealDbImportService.importAllAreas(maxPerArea)
            : Map.of("TOTAL", 0);

        int importedFoods = Boolean.TRUE.equals(includeOpenFoodFacts)
            ? openFoodFactsImportService.importFoods(parseCsv(countries), pages, pageSize)
            : 0;

        Map<String, Integer> dummyJsonImport = Boolean.TRUE.equals(includeDummyJson)
            ? dummyJsonRecipeImportService.importRecipes(dummyJsonPageSize, dummyJsonMaxRecipes)
            : Map.of("TOTAL_IMPORTED", 0, "NEW_INGREDIENTS", 0, "PROCESSED_RECIPES", 0, "SKIPPED_EXISTING", 0);

        int totalCuisineDishes = cuisineImport.getOrDefault("TOTAL", 0);
        int totalAreaDishes = areaImport.getOrDefault("TOTAL", 0);
        int totalDummyDishes = dummyJsonImport.getOrDefault("TOTAL_IMPORTED", 0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dishesFromCuisineTargets", totalCuisineDishes);
        summary.put("dishesFromMealDbAreas", totalAreaDishes);
        summary.put("dishesFromDummyJson", totalDummyDishes);
        summary.put("ingredientsFromOpenFoodFacts", importedFoods);
        summary.put("totalDishesImported", totalCuisineDishes + totalAreaDishes + totalDummyDishes);
        summary.put("totalImportSignals", totalCuisineDishes + totalAreaDishes + totalDummyDishes + importedFoods);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("source", "TheMealDB + OpenFoodFacts + DummyJSON");
        response.put("summary", summary);
        response.put("dishesByCuisine", cuisineImport);
        response.put("dishesByArea", areaImport);
        response.put("dummyJson", dummyJsonImport);
        response.put("ingredientsFromOpenFoodFacts", importedFoods);
        return response;
    }

    private List<String> parseCsv(String value) {
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .collect(Collectors.toList());
    }

    private Map<String, Object> buildImageImport(
        Boolean includeIngredients,
        Boolean includeDishes,
        Integer ingredientLimit,
        Integer dishLimit,
        Boolean overwriteExisting
    ) {
        Map<String, Object> result = wikimediaImageImportService.importImages(
            includeIngredients,
            includeDishes,
            ingredientLimit,
            dishLimit,
            overwriteExisting
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("source", "Wikimedia Commons");
        response.putAll(result);
        return response;
    }

    private Map<String, Object> buildSweetsDessertImport(
        String countries,
        Integer pages,
        Integer pageSize,
        Integer maxPerQuery,
        Integer maxMealDbDesserts,
        Boolean includeCuratedFallback
    ) {
        return sweetsDatasetImportService.importSweetsAndDesserts(
            parseCsv(countries),
            pages,
            pageSize,
            maxPerQuery,
            maxMealDbDesserts,
            Boolean.TRUE.equals(includeCuratedFallback)
        );
    }

    private Map<String, Object> buildDatasetCorrection(Boolean promoteFactChecked) {
        return datasetCorrectionService.correctAllDatasets(Boolean.TRUE.equals(promoteFactChecked));
    }

    private <T> T runImportExclusively(Supplier<T> action) {
        if (!importInProgress.compareAndSet(false, true)) {
            throw new ResponseStatusException(CONFLICT, "An import is already running. Please wait for it to finish.");
        }
        try {
            return action.get();
        } finally {
            importInProgress.set(false);
        }
    }
}
