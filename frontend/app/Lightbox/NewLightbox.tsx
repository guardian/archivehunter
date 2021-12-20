import React, {useContext, useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import {makeStyles, Snackbar, Typography} from "@material-ui/core";
import {
    ArchiveEntry,
    LightboxBulk,
    LightboxBulkResponse,
    LightboxDetailsResponse, LightboxEntry, ObjectListResponse, RestoreStatusResponse,
    UserDetails,
    UserDetailsResponse
} from "../types";
import UserSelector from "../common/UserSelector";
import BulkSelectionsScroll from "./BulkSelectionsScroll";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";
import NewSearchComponent from "../common/NewSearchComponent";
import MuiAlert from "@material-ui/lab/Alert";
import EntryDetails from "../Entry/EntryDetails";
import LightboxDetailsInsert from "./LightboxDetailsInsert";
import {UserContext} from "../Context/UserContext";
import BrowseFilter from "../browse/BrowseFilter";

const useStyles = makeStyles({
    browserWindow: {
        display: "grid",
        gridTemplateColumns: "repeat(20, 5%)",
        gridTemplateRows: "[top] 40px [title-area] 200px [filter-area] 80px [info-area] auto [bottom]",
        height: "95vh"
    },
    userNameBox: {
        gridColumnStart: 1,
        gridColumnEnd: 4,
        gridRowStart: "top",
        gridRowEnd: "title-area"
    },
    userNameText: {
        fontSize: "1.6rem",
        fontWeight: 800,
        whiteSpace: "nowrap"
    },
    userSelectorBox: {
        gridColumnStart: -5,
        gridColumnEnd: -1,
        gridRowStart: "top",
        gridRowEnd: "title-area"
    },
    bulkSelectorBox: {
        gridColumnStart: 1,
        gridColumnEnd: -5,
        gridRowStart: "title-area",
        gridRowEnd: "filter-area",
        overflowY: "hidden",
        overflowX: "auto"
    },
    itemsArea: {
        gridColumnStart: 1,
        gridColumnEnd: -4,
        gridRowStart: "info-area",
        gridRowEnd: "bottom",
        padding: "1em"
    },
    detailsArea: {
        gridColumnStart: -5,
        gridColumnEnd: -1,
        gridRowStart: "title-area",
        gridRowEnd: "bottom",
        padding: "1em",
        borderLeft: "1px"
    },
    filterArea: {
        gridColumnStart: 1,
        gridColumnEnd: -5,
        gridRowStart: "filter-area",
        gridRowEnd: "info-area",
        padding: "1em"
    }
});

const NewLightbox:React.FC<RouteComponentProps> = (props) => {
    const classes = useStyles();
    //bulk selector related state
    const [selectedUser, setSelectedUser] = useState<string>("my");
    const [selectedBulk, setSelectedBulk] = useState<string|undefined>(undefined);
    const [expiryDays, setExpiryDays] = useState<number>(10);
    //general state
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [showingAlert, setShowingAlert] = useState(false);
    const [bulkSelectionsCount, setBulkSelectionsCount] = useState(0);
    const [loading, setLoading] = useState(false);
    //lightbox-related information for every entry associated with
    const [lightboxDetails, setLightboxDetails] = useState<Record<string, LightboxEntry>>({});
    //used to refresh the lightbox state if a user removes and object
    const [newlyLightboxed, setNewlyLightboxed] = useState<string[]>([]);
    //entry view related state
    const [selectedEntry, setSelectedEntry] = useState<ArchiveEntry|undefined>(undefined);
    const [pageSize, setPageSize] = useState(100);
    const [itemLimit, setItemLimit] = useState(300);
    const [basicSearchUrl, setBasicSearchUrl] = useState<string|undefined>(undefined);
    const [showingArchiveSpinner, setShowingArchiveSpinner] = useState(false);
    const [filterString, setFilterString] = useState<string>("");

    const userContext = useContext(UserContext);

    const userDisplayName = ()=>{
        if(selectedUser!=="my") return selectedUser + "'s ";
        if(userContext.profile) return "My ";
        return undefined;
    }

    const performLoad = () => {
        const detailsRequest = axios.get<LightboxDetailsResponse>("/api/lightbox/" + selectedUser+"/details");
        const configRequest = axios.get<ObjectListResponse<string>>("/api/config");
        return Promise.all([detailsRequest, configRequest]);
    }

    const refreshData = async () => {
        setLoading(true);
        try {
            const results = await performLoad();

            const detailsResult = results[0].data as LightboxDetailsResponse;
            const configResult = results[1].data as ObjectListResponse<string>;
            setLightboxDetails(detailsResult.entries)
            setExpiryDays(configResult.entries.length>0 ? parseInt(configResult.entries[0]) : 10);

        } catch(err) {
            console.error("Could not load in lightbox data: ", err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    /**
     * updates our record of the archive status for the currently selected item
     */
    const checkArchiveStatus = async (archiveEntryId:string) => {
        try {
            const response = await axios.get<RestoreStatusResponse>(`/api/archive/status/${archiveEntryId}?user=${selectedUser}`)

            setShowingArchiveSpinner(false);
            if(lightboxDetails.hasOwnProperty(response.data.fileId)) {
                const updatedEntry = Object.assign({},
                    lightboxDetails[response.data.fileId],
                    {
                        restoreStatus: response.data.restoreStatus,
                        availableUntil: response.data.expiry
                    });

                setLightboxDetails((prevState)=>Object.assign({}, prevState, {[response.data.fileId]: updatedEntry}));
            } else {
                console.error("could not find record with id ", response.data.fileId, " in the lightbox data");
                setLastError("internal error: could not find record with id " + response.data.fileId);
                setShowingAlert(true);
            }
        } catch(err) {
            console.error(err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
            setShowingArchiveSpinner(false);
        }
    }

    const redoRestore = async (entryId:string) => {
        const url = `/api/lightbox/${selectedUser}/redoRestore/${entryId}`;
        setShowingArchiveSpinner(true);

        try {
            await axios.put(url);
            console.info("redo restore requested, updating archive status in 1s...");

            window.setTimeout(()=>checkArchiveStatus(entryId), 1000);

        } catch(err) {
            console.error(`could not request redo restore on ${entryId}: `, err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
            setShowingArchiveSpinner(false);
        }
    }

    //update the search view if the selected user or bulk param changed
    useEffect(()=>{
        const userParam:Record<string,string> = {user: selectedUser};
        const withBulkIdParam:Record<string,string> = selectedBulk ? Object.assign({bulkId: selectedBulk}, userParam) : userParam;

        const paramString = Object.keys(withBulkIdParam)
            .map(key=>`${key}=${withBulkIdParam[key]}`)
            .join("&");

        setBasicSearchUrl(`/api/search/myLightBox?${paramString}`);
    }, [selectedUser, selectedBulk]);

    //reload the search if the currently selected bulk changes
    useEffect(()=>{
        refreshData();
    }, [selectedBulk, selectedUser]);

    const handleComponentError = (desc:string) => {
        setLastError(desc);
        setShowingAlert(true);
    }

    const closeAlert = ()=> {
        setShowingAlert(false);
    }

    return <div className={classes.browserWindow}>
        <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
            <MuiAlert severity="error" onClose={closeAlert}>{lastError}</MuiAlert>
        </Snackbar>
        <div className={classes.userNameBox}>
            <Typography className={classes.userNameText}>{userDisplayName() ? `${userDisplayName()} Lightbox` : "Lightbox"}</Typography>
        </div>

        { userContext.profile?.isAdmin ? <div className={classes.userSelectorBox}>
            <UserSelector onChange={(newUser)=>setSelectedUser(newUser)} selectedUser={selectedUser}/>
        </div> : null }

        <div className={classes.bulkSelectorBox}>
            <BulkSelectionsScroll onSelected={(newId:string|undefined)=>setSelectedBulk(newId)}
                                  currentSelection={selectedBulk}
                                  forUser={selectedUser}
                                  isAdmin={userContext.profile?.isAdmin ?? false}
                                  expiryDays={expiryDays}
                                  onError={handleComponentError}
            />
        </div>
        <div className={classes.filterArea}>
        <BrowseFilter filterString={filterString}
                      filterStringChanged={(newString)=>setFilterString(newString)}/>
        </div>
        <div className={classes.itemsArea}>
            <NewSearchComponent pageSize={pageSize}
                                itemLimit={itemLimit}
                                basicQueryUrl={basicSearchUrl}
                                newlyLightboxed={newlyLightboxed}
                                selectedEntry={selectedEntry}
                                onEntryClicked={(newEntry)=>setSelectedEntry(newEntry)}
                                onErrorOccurred={handleComponentError}
                                filterString={filterString}
            />
        </div>

        <div className={classes.detailsArea}>
            {selectedEntry ? <EntryDetails entry={selectedEntry}
                          autoPlay={true}
                          showJobs={true}
                          loadJobs={false}
                          lightboxedCb={(entryId:string)=>setNewlyLightboxed((prevState) => prevState.concat(entryId))}
                          preLightboxInsert={
                              <LightboxDetailsInsert
                                  checkArchiveStatusClicked={()=>checkArchiveStatus(selectedEntry.id)}
                                  redoRestoreClicked={redoRestore}
                                  archiveEntryId={selectedEntry?.id}
                                  archiveEntryPath={selectedEntry?.path}
                                  lightboxEntry={lightboxDetails[selectedEntry.id]}
                                  user={selectedUser}
                                  showingArchiveSpinner={showingArchiveSpinner}
                              />
                          }
            /> : undefined }
        </div>
    </div>
}

export default NewLightbox;