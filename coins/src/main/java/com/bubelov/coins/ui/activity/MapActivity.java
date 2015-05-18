package com.bubelov.coins.ui.activity;

import android.animation.Animator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Configuration;
import android.database.Cursor;
import android.location.Location;
import android.os.Bundle;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.ListPopupWindow;
import android.support.v7.widget.Toolbar;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.bubelov.coins.App;
import com.bubelov.coins.Constants;
import com.bubelov.coins.MerchantsCache;
import com.bubelov.coins.R;
import com.bubelov.coins.database.Database;
import com.bubelov.coins.event.DatabaseUpToDateEvent;
import com.bubelov.coins.event.DatabaseSyncingEvent;
import com.bubelov.coins.event.NewMerchantsLoadedEvent;
import com.bubelov.coins.loader.MerchantsLoader;
import com.bubelov.coins.manager.UserNotificationManager;
import com.bubelov.coins.model.Currency;
import com.bubelov.coins.model.Merchant;
import com.bubelov.coins.service.DatabaseSyncService;
import com.bubelov.coins.ui.widget.CurrenciesFilterPopup;
import com.bubelov.coins.ui.widget.DrawerMenu;
import com.bubelov.coins.ui.widget.MerchantDetailsView;
import com.bubelov.coins.util.OnCameraChangeMultiplexer;
import com.bubelov.coins.util.StaticClusterRenderer;
import com.bubelov.coins.util.Utils;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.maps.android.clustering.ClusterManager;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.squareup.otto.Subscribe;

import java.util.ArrayList;
import java.util.Collection;

public class MapActivity extends AbstractActivity implements LoaderManager.LoaderCallbacks<Cursor>, DrawerMenu.OnMenuItemSelectedListener {
    private static final String TAG = MapActivity.class.getSimpleName();

    private static final String MERCHANT_ID_EXTRA = "merchant_id";

    private static final int MERCHANTS_LOADER = 0;

    private static final int REQUEST_CHECK_LOCATION_SETTINGS = 0;

    private Toolbar merchantToolbar;

    private DrawerLayout drawer;

    private ActionBarDrawerToggle drawerToggle;

    private GoogleMap map;

    private ClusterManager<Merchant> merchantsManager;

    private String amenity;

    private SlidingUpPanelLayout slidingLayout;

    private MerchantDetailsView merchantDetails;

    private View loader;

    private BitmapDescriptor merchantDescriptor;

    private GoogleApiClient googleApiClient;

    private Merchant selectedMerchant;

    private boolean databaseSyncing;

    private boolean saveCameraPositionFlag;

    private CameraUpdate cameraBeforeSelection;

    public static Intent newShowMerchantIntent(Context context, long merchantId) {
        Intent intent = new Intent(context, MapActivity.class);
        intent.putExtra(MERCHANT_ID_EXTRA, merchantId);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle("All");
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        merchantToolbar = findView(R.id.merchant_toolbar);
        merchantToolbar.setNavigationOnClickListener(v -> slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED));

        drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawerToggle = new DrawerToggle(this, drawer, android.R.string.ok, android.R.string.ok);
        drawer.setDrawerListener(drawerToggle);

        DrawerMenu drawerMenu = (DrawerMenu) findViewById(R.id.left_drawer);
        drawerMenu.setSelected(R.id.all);
        drawerMenu.setItemSelectedListener(this);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        map = mapFragment.getMap();
        map.setMyLocationEnabled(true);
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setCompassEnabled(false);
        map.getUiSettings().setMapToolbarEnabled(false);

        initClustering();

        map.setOnCameraChangeListener(new OnCameraChangeMultiplexer(merchantsManager, new CameraChangeListener()));

        LatLngBounds bounds = map.getProjection().getVisibleRegion().latLngBounds;
        getSupportLoaderManager().initLoader(MERCHANTS_LOADER, MerchantsLoader.prepareArguments(bounds, amenity), this);

        merchantDetails = findView(R.id.merchant_details);

        loader = findView(R.id.loader);
        loader.animate().alpha(0.7f).setDuration(700).setListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (loader.getAlpha() == 0.7f) {
                    loader.animate().alpha(1.0f).setDuration(700).setListener(this);
                } else {
                    loader.animate().alpha(0.7f).setDuration(700).setListener(this);
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });

        merchantDescriptor = BitmapDescriptorFactory.fromResource(R.drawable.ic_place_white_48dp);

        if (savedInstanceState == null) {
            if (getIntent().hasExtra(MERCHANT_ID_EXTRA)) {
                selectMerchant(getIntent().getLongExtra(MERCHANT_ID_EXTRA, -1));
                slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.ANCHORED);
            } else {
                googleApiClient = new GoogleApiClient.Builder(this)
                        .addApi(LocationServices.API)
                        .addConnectionCallbacks(new LocationApiConnectionCallbacks())
                        .addOnConnectionFailedListener(new LocationAliConnectionFailedListener())
                        .build();

                googleApiClient.connect();
            }
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();

        slidingLayout = findView(R.id.sliding_panel);
        slidingLayout.setPanelHeight(0);
        slidingLayout.setAnchorPoint(0.5f);

        slidingLayout.setPanelSlideListener(new SlidingUpPanelLayout.PanelSlideListener() {
            @Override
            public void onPanelSlide(View view, float offset) {
                if (saveCameraPositionFlag) {
                    cameraBeforeSelection = CameraUpdateFactory.newCameraPosition(map.getCameraPosition());
                    saveCameraPositionFlag = false;
                }

                if (offset < 0.2) {
                    if (!getSupportActionBar().isShowing()) {
                        getSupportActionBar().show();
                        merchantToolbar.setVisibility(View.GONE);
                    }

                    if (databaseSyncing) {
                        loader.setVisibility(View.VISIBLE);
                    }
                } else {
                    if (getSupportActionBar().isShowing()) {
                        getSupportActionBar().hide();
                        merchantToolbar.setVisibility(View.VISIBLE);
                    }

                    if (loader.getVisibility() == View.VISIBLE) {
                        loader.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public void onPanelCollapsed(View view) {
                map.setPadding(0, 0, 0, 0);
                map.getUiSettings().setAllGesturesEnabled(true);
                merchantDetails.setMultilineHeader(false);
                map.animateCamera(cameraBeforeSelection);
            }

            @Override
            public void onPanelExpanded(View view) {

            }

            @Override
            public void onPanelAnchored(View view) {
                map.setPadding(0, 0, 0, Utils.getScreenHeight(MapActivity.this) / 2 + Utils.getStatusBarHeight(getApplicationContext()));
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(selectedMerchant.getPosition(), 13));
                map.getUiSettings().setAllGesturesEnabled(false);
                merchantDetails.setMultilineHeader(true);
            }

            @Override
            public void onPanelHidden(View view) {

            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startService(DatabaseSyncService.makeIntent(this, false));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_map, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        int id = item.getItemId();

        switch (id) {
            case R.id.action_filter:
                ListPopupWindow popup = new CurrenciesFilterPopup(this);
                popup.setAnchorView(findViewById(R.id.anchor_upper_right));
                popup.setHeight(drawer.getHeight() / 10 * 9);
                popup.show();

                popup.setOnDismissListener(() -> {
                    App.getInstance().getMerchantsCache().invalidate();
                    reloadMerchants();
                });

                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent.hasExtra(MERCHANT_ID_EXTRA)) {
            selectMerchant(intent.getLongExtra(MERCHANT_ID_EXTRA, -1));
            slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.ANCHORED);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable("", map.getCameraPosition());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CHECK_LOCATION_SETTINGS && resultCode == RESULT_OK) {
            findLocation();
        }

        if (requestCode == REQUEST_CHECK_LOCATION_SETTINGS && resultCode == RESULT_CANCELED) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(Constants.SAN_FRANCISCO_LATITUDE, Constants.SAN_FRANCISCO_LONGITUDE), 13));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public void onBackPressed() {
        if (slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.ANCHORED) || slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.EXPANDED)) {
            slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (id == MERCHANTS_LOADER) {
            return new MerchantsLoader(this, args);
        } else {
            return null;
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        Collection<Merchant> merchants = new ArrayList<>();

        while (data.moveToNext()) {
            Merchant merchant = new Merchant();
            merchant.setId(data.getLong(data.getColumnIndex(Database.Merchants._ID)));
            merchant.setLatitude(data.getDouble(data.getColumnIndex(Database.Merchants.LATITUDE)));
            merchant.setLongitude(data.getDouble(data.getColumnIndex(Database.Merchants.LONGITUDE)));
            merchant.setAmenity(data.getString(data.getColumnIndex(Database.Merchants.AMENITY)));

            merchants.add(merchant);
        }

        onMerchantsLoaded(merchants);
    }

    private void onMerchantsLoaded(Collection<Merchant> merchants) {
        merchantsManager.clearItems();
        merchantsManager.addItems(merchants);
        merchantsManager.cluster();
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        merchantsManager.clearItems();
        merchantsManager.cluster();
    }

    @Override
    public void onMenuItemSelected(int id, com.bubelov.coins.ui.widget.MenuItem menuItem) {
        drawer.closeDrawer(Gravity.LEFT);

        if (id != R.id.settings && id != R.id.help_and_feedback) {
            getSupportActionBar().setTitle(menuItem.getText());
        }

        if (id == R.id.settings) {
            startActivity(new Intent(this, SettingsActivity.class), ActivityOptionsCompat.makeSceneTransitionAnimation(this).toBundle());
            return;
        }

        switch (id) {
            case R.id.all:
                amenity = null;
                break;
            case R.id.atms:
                amenity = "atm";
                break;
            case R.id.cafes:
                amenity = "cafe";
                break;
            case R.id.restaurants:
                amenity = "restaurant";
                break;
            case R.id.bars:
                amenity = "bar";
                break;
            case R.id.hotels:
                amenity = "hotel";
                break;
            case R.id.car_washes:
                amenity = "car_wash";
                break;
            case R.id.gas_stations:
                amenity = "fuel";
                break;
            case R.id.hospitals:
                amenity = "hospital";
                break;
            case R.id.laundry:
                amenity = "dry_cleaning";
                break;
            case R.id.movies:
                amenity = "cinema";
                break;
            case R.id.parking:
                amenity = "parking";
                break;
            case R.id.pharmacies:
                amenity = "pharmacy";
                break;
            case R.id.pizza:
                amenity = "pizza";
                break;
            case R.id.taxi:
                amenity = "taxi";
                break;
        }

        reloadMerchants();
    }

    private void findLocation() {
        Location lastLocation = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);
        UserNotificationManager notificationManager = new UserNotificationManager(this);
        boolean notificationAreaNotSet = notificationManager.getNotificationAreaCenter() == null;

        if (lastLocation != null) {
            LatLng latLng = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 13));

            if (notificationAreaNotSet) {
                notificationManager.setNotificationAreaCenter(latLng);
            }
        } else {
            LocationRequest locationRequest = LocationRequest.create().setNumUpdates(1);
            LocationSettingsRequest locationSettingsRequest = new LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build();
            PendingResult<LocationSettingsResult> locationSettingsResult = LocationServices.SettingsApi.checkLocationSettings(googleApiClient, locationSettingsRequest);

            locationSettingsResult.setResultCallback(result -> {
                switch (result.getStatus().getStatusCode()) {
                    case LocationSettingsStatusCodes.SUCCESS:
                        LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, location -> {
                            LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 13));

                            if (notificationAreaNotSet) {
                                notificationManager.setNotificationAreaCenter(latLng);
                            }
                        });

                        break;

                    case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                        try {
                            result.getStatus().startResolutionForResult(MapActivity.this, REQUEST_CHECK_LOCATION_SETTINGS);
                        } catch (IntentSender.SendIntentException exception) {
                            // Ignoring
                        }

                        break;
                }
            });
        }
    }

    private void initClustering() {
        merchantsManager = new ClusterManager<>(this, map);
        PlacesRenderer renderer = new PlacesRenderer(this, map, merchantsManager);
        merchantsManager.setRenderer(renderer);
        renderer.setOnClusterItemClickListener(new ClusterItemClickListener());

        map.setOnCameraChangeListener(merchantsManager);
        map.setOnMarkerClickListener(merchantsManager);
        map.setOnMapClickListener(latLng -> slidingLayout.setPanelHeight(0));
    }

    @Subscribe
    public void onDatabaseSyncing(DatabaseSyncingEvent event) {
        databaseSyncing = true;

        if (!slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.ANCHORED)) {
            loader.setVisibility(View.VISIBLE);
        }
    }

    @Subscribe
    public void onNewMerchantsLoaded(NewMerchantsLoadedEvent event) {
        reloadMerchants();
    }

    @Subscribe
    public void onDatabaseSyncFinished(DatabaseUpToDateEvent event) {
        databaseSyncing = false;
        loader.setVisibility(View.GONE);
    }

    private void reloadMerchants() {
        LatLngBounds bounds = map.getProjection().getVisibleRegion().latLngBounds;
        MerchantsCache cache = getApp().getMerchantsCache();

        if (cache.isInitialized()) {
            onMerchantsLoaded(cache.getMerchants(bounds, amenity));
        } else {
            getSupportLoaderManager().restartLoader(MERCHANTS_LOADER, MerchantsLoader.prepareArguments(bounds, amenity), MapActivity.this);
        }
    }

    private Merchant getMerchant(long id) {
        Merchant merchant = new Merchant();

        Cursor cursor = getContentResolver().query(Database.Merchants.CONTENT_URI,
                new String[] { Database.Merchants.LATITUDE, Database.Merchants.LONGITUDE, Database.Merchants.NAME, Database.Merchants.DESCRIPTION, Database.Merchants.PHONE, Database.Merchants.WEBSITE, Database.Merchants.ADDRESS, Database.Merchants.OPENING_HOURS },
                "_id = ?",
                new String[] { String.valueOf(id) },
                null);

        if (cursor.moveToNext()) {
            merchant.setLatitude(cursor.getDouble(cursor.getColumnIndex(Database.Merchants.LATITUDE)));
            merchant.setLongitude(cursor.getDouble(cursor.getColumnIndex(Database.Merchants.LONGITUDE)));
            merchant.setName(cursor.getString(cursor.getColumnIndex(Database.Merchants.NAME)));
            merchant.setDescription(cursor.getString(cursor.getColumnIndex(Database.Merchants.DESCRIPTION)));
            merchant.setPhone(cursor.getString(cursor.getColumnIndex(Database.Merchants.PHONE)));
            merchant.setWebsite(cursor.getString(cursor.getColumnIndex(Database.Merchants.WEBSITE)));
            merchant.setAddress(cursor.getString(cursor.getColumnIndex(Database.Merchants.ADDRESS)));
            merchant.setOpeningHours(cursor.getString(cursor.getColumnIndex(Database.Merchants.OPENING_HOURS)));
        }

        cursor.close();

        cursor = getContentResolver().query(Database.Merchants.CONTENT_URI.buildUpon().appendPath(String.valueOf(id)).appendPath("currencies").build(),
                new String[] { Database.Currencies.NAME },
                null,
                null,
                null);

        merchant.setCurrencies(new ArrayList<>());

        while (cursor.moveToNext()) {
            Currency currency = new Currency();
            currency.setName(cursor.getString(0));
            merchant.getCurrencies().add(currency);
        }

        cursor.close();

        return merchant;
    }

    private class DrawerToggle extends ActionBarDrawerToggle {
        public DrawerToggle(Activity activity, DrawerLayout drawerLayout, int openDrawerContentDescRes, int closeDrawerContentDescRes) {
            super(activity, drawerLayout, openDrawerContentDescRes, closeDrawerContentDescRes);
        }

        @Override
        public void onDrawerOpened(View drawerView) {
            super.onDrawerOpened(drawerView);
            invalidateOptionsMenu();
        }

        @Override
        public void onDrawerClosed(View drawerView) {
            super.onDrawerClosed(drawerView);
            invalidateOptionsMenu();
        }
    }

    private class PlacesRenderer extends StaticClusterRenderer<Merchant> {
        public PlacesRenderer(Context context, GoogleMap map, ClusterManager<Merchant> clusterManager) {
            super(context, map, clusterManager);
        }

        @Override
        protected void onBeforeClusterItemRendered(Merchant item, MarkerOptions markerOptions) {
            super.onBeforeClusterItemRendered(item, markerOptions);
            markerOptions.icon(merchantDescriptor).anchor(Constants.MAP_MARKER_ANCHOR_U, Constants.MAP_MARKER_ANCHOR_V);
        }
    }

    private class CameraChangeListener implements GoogleMap.OnCameraChangeListener {
        @Override
        public void onCameraChange(CameraPosition cameraPosition) {
            reloadMerchants();

            if (slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.COLLAPSED)) {
                saveCameraPositionFlag = true;
            }
        }
    }

    private class ClusterItemClickListener implements ClusterManager.OnClusterItemClickListener<Merchant> {
        @Override
        public boolean onClusterItemClick(Merchant merchant) {
//            new Thread(new Runnable() {
//                @Override
//                public void run() {
//                    SQLiteDatabase db = App.getInstance().getDatabaseHelper().getReadableDatabase();
//
//                    Random random = new Random(666);
//
//                    long start = System.currentTimeMillis();
//
//                    for (int i = 0; i < 500; i++) {
//                        Cursor cursor = db.rawQuery("select distinct m._id, m.latitude, m.longitude, m.name, m.description from merchants as m join currencies_merchants as mc on m._id = mc.merchant_id join currencies c on c._id = mc.currency_id where (latitude between ? and ?) and (longitude between ? and ?) and c.show_on_map = 1",
//                                new String[] { String.valueOf(-180.0f + random.nextFloat() * 360.0f), String.valueOf(-180.0f + random.nextFloat() * 360.0f), String.valueOf(-180.0f + random.nextFloat() * 360.0f), String.valueOf(-180.0f + random.nextFloat() * 360.0f) });
//
//                        //Log.d(TAG, "Count: " + cursor.getCount());
//
//                        while (cursor.moveToNext()) {
//                            int x = 1;
//                        }
//
//                        cursor.close();
//                    }
//
//                    long execTime = System.currentTimeMillis() - start;
//                    Log.d(TAG, "Execution time: " + execTime);
//                    Log.d(TAG, "Avg query: " + (float) execTime / 10000.0f);
//                }
//            }).start();

            selectedMerchant = merchant;
            selectMerchant(merchant.getId());
            return false;
        }
    }



    private void selectMerchant(long merchantId) {
        saveCameraPositionFlag = true;
        selectedMerchant = getMerchant(merchantId);
        merchantDetails.setMerchant(selectedMerchant);
        slidingLayout.setPanelHeight(merchantDetails.getHeaderHeight());
    }

    private class LocationApiConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {
        @Override
        public void onConnected(Bundle bundle) {
            findLocation();
        }

        @Override
        public void onConnectionSuspended(int cause) {
            showToast("Connection to location API was suspended");
        }
    }

    private class LocationAliConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {
        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            showToast("Couldn't connect to location API");
        }
    }
}