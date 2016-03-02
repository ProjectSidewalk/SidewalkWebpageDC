/**
 * Mission module
 * @param parameters
 * @returns {{className: string}}
 * @constructor
 */
function Mission(parameters) {
    var self = { className: "Mission" },
        properties = {
            regionId: null,
            label: null,
            missionId: null,
            level: null
        };

    function _init(parameters) {
        if ("regionId" in parameters) setProperty("regionId", parameters.regionId);
        if ("label" in parameters) setProperty("label", parameters.label);
        if ("missionId" in parameters) setProperty("missionId", parameters.missionId);
        if ("level" in parameters) setProperty("level", parameters.level);
    }

    /** Returns a property */
    function getProperty (key) {
        return key in properties ? properties[key] : key;
    }

    /** Sets a property */
    function setProperty (key, value) {
        properties[key] = value;
        return this;
    }

    _init(parameters);

    self.getProperty = getProperty;
    self.setProperty = setProperty;
    return self;
}

/**
 * MissionContainer module
 * @param parameters
 * @returns {{className: string}}
 * @constructor
 */
function MissionContainer (parameters) {
    var self = { className: "MissionContainer" },
        missionStoreByRegionId = { "noRegionId" : []},
        completedMissions = [];

    function _init (parameters) {
    }

    /** Push the completed mission */
    function addCompletedMission (mission) {
        completedMissions.push(mission);

        if ("regionId" in mission) {
            // Add the region id to missionStoreByRegionId if it's not there already
            if (!getMissionsByRegionId(mission.regionId)) missionStoreByRegionId[mission.regionId] = [];

            // Add the mission into missionStoreByRegionId if it's not there alread
            var missionIds = missionStoreByRegionId[mission.regionId].map(function (x) { return x.missionId; });
            if (missionIds.indexOf(mission.missionId) < 0) missionStoreByRegionId[regionId].push(mission);
        }
    }

    /** Get all the completed missions */
    function getCompletedMissions () {
        return completedMissions;
    }

    /** Get all the completed missions with the given region id */
    function getMissionsByRegionId (regionId) {
        if (!(regionId in missionStoreByRegionId)) missionStoreByRegionId[regionId] = [];
        return missionStoreByRegionId[regionId];
    }

    _init(parameters);

    self.addCompletedMission = addCompletedMission;
    self.getCompletedMissions = getCompletedMissions;
    self.getMissionsByRegionId = getMissionsByRegionId;
    return self;
}

/**
 * MissionFactory module
 * @param parameters
 * @returns {{className: string}}
 * @constructor
 */
function MissionFactory (parameters) {
    var self = { className: "MissionFactory"};

    function _init (parameters) {    }

    /** Create an instance of a mission object */
    function create (regionId, missionId, label, level) {
        return new Mission({ regionId: regionId, missionId: missionId, label: label, level: level });
    }

    /** Get the next mission */
    function nextMission () {
        console.debug("Query and create the next mission.");
    }

    _init(parameters);

    self.create = create;
    self.nextMission = nextMission;

    return self;
}