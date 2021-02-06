import React, {useEffect, useState} from "react";
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

const useStyles = makeStyles({
    browserWindow: {
        display: "grid",
        gridTemplateColumns: "repeat(20, 5%)",
        gridTemplateRows: "[top] 40px [title-area] 200px [info-area] auto [bottom]",
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
        gridRowEnd: "info-area"
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
    }
});

const NewLightbox:React.FC<RouteComponentProps> = (props) => {
    const classes = useStyles();
    const [userDetails, setUserDetails] = useState<UserDetails|undefined>(undefined);
    //bulk selector related state
    const [selectedUser, setSelectedUser] = useState<string>("my");
    const [bulkSelections, setBulkSelections] = useState<LightboxBulk[]>([]);
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

    const userDisplayName = ()=>{
        if(selectedUser!=="my") return selectedUser;
        if(userDetails) return userDetails.firstName + " " + userDetails.lastName;
        return undefined;
    }

    const bulkSearchDeleteRequested = async (entryId:string) => {
        try {
            await axios.delete("/api/lightbox/"+selectedUser+"/bulk/" + entryId);
            console.log("lightbox entry " + entryId + " deleted.");
            //if we are deleting the current selection, the update the selection to undefined otherwise do a no-op update
            //to trugger reload
            const updatedSelected = selectedBulk===entryId ? undefined : selectedBulk;

            setBulkSelections((prevState) => prevState.filter(entry=>entry.id!==entryId));
            setSelectedBulk(updatedSelected);
        } catch(err) {
            console.error(err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    const performLoad = () => {
        const detailsRequest = axios.get<LightboxDetailsResponse>("/api/lightbox/" + selectedUser+"/details");
        //const summaryRequest = axios.get<ObjectListResponse<ArchiveEntry>>(`/api/search/myLightBox?user=${selectedUser}&size=${pageSize}`);
        const loginDetailsRequest = axios.get<UserDetailsResponse>("/api/loginStatus");
        const bulkSelectionsRequest = axios.get<LightboxBulkResponse>("/api/lightbox/" + selectedUser+"/bulks");
        const configRequest = axios.get<ObjectListResponse<string>>("/api/config");
        return Promise.all([detailsRequest, loginDetailsRequest, bulkSelectionsRequest, configRequest]);
    }

    const refreshData = async () => {
        setLoading(true);
        try {
            const results = await performLoad();

            const detailsResult = results[0].data as LightboxDetailsResponse;
            //const summaryResult = results[1].data as ObjectListResponse<ArchiveEntry>;
            const loginDetailsResult = results[1].data as UserDetailsResponse;
            const bulkSelectionsResult = results[2].data as LightboxBulkResponse;
            const configResult = results[3].data as ObjectListResponse<string>;
            // setSearchResults(summaryResult.entries.map(entry=>
            //     Object.assign({}, entry, {details: detailsResult.entries.includes(entry.id) ? detailsResult.entries[entry.id] : null}))
            // );
            setLightboxDetails(detailsResult.entries)
            setUserDetails(loginDetailsResult);
            setBulkSelections(bulkSelectionsResult.entries);
            setBulkSelectionsCount(bulkSelectionsResult.entryCount);
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

            console.info(response.data);
            //const itemIndex = indexForFileid(response.data.fileId);
            // if (itemIndex === -1) {
            //     console.error("Could not find file ID " + response.data.fileId + " in the component's data??");
            //     //setShowingArchivedSpinner(false);
            // } else {
            //     const updatedDetails = Object.assign({}, lightboxDetails[itemIndex], {
            //         restoreStatus: response.data.restoreStatus,
            //         availableUntil: response.data.expiry
            //     });
            //
            //     //setLightboxDetails((prevState) => prevState.slice(0, itemIndex).concat(updatedDetails, prevState.slice(itemIndex+1, prevState.length)));
            //
            //     this.updateSearchResults(updatedEntry, itemIndex, this.state.showingPreview.id).then(() => {
            //         if (response.data.restoreStatus === "RS_UNNEEDED") {
            //             this.setState({showingArchiveSpinner: false, extraInfo: "Not in deep-freeze"})
            //         } else if (this.state.extraInfo !== "") {
            //             this.setState({
            //                 showingArchiveSpinner: false,
            //                 selectedRestoreStatus: response.data.restoreStatus,
            //                 extraInfo: ""
            //             })
            //         }
            //     })
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
            //this.setState({showingArchiveSpinner: false, selectedRestoreStatus: err.response.data && err.response.data.detail ? err.response.data.detail : ""});
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
    }, [selectedBulk]);

    useEffect(()=>{
        refreshData();
    }, []);

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
            <Typography className={classes.userNameText}>{userDisplayName() ? `${userDisplayName()}'s Lightbox` : "Lightbox"}</Typography>
        </div>

        { userDetails?.isAdmin ? <div className={classes.userSelectorBox}>
            <UserSelector onChange={(newUser)=>setSelectedUser(newUser)} selectedUser={selectedUser}/>
        </div> : null }

        <div className={classes.bulkSelectorBox}>
            <BulkSelectionsScroll entries={bulkSelections}
                                  onSelected={(newId:string)=>setSelectedBulk(newId)}
                                  onDeleteClicked={bulkSearchDeleteRequested}
                                  currentSelection={selectedBulk}
                                  forUser={selectedUser}
                                  isAdmin={userDetails?.isAdmin ?? false}
                                  expiryDays={expiryDays}
                                  onError={handleComponentError}
            />
        </div>

        <div className={classes.itemsArea}>
            <NewSearchComponent pageSize={pageSize}
                                itemLimit={itemLimit}
                                basicQueryUrl={basicSearchUrl}
                                newlyLightboxed={newlyLightboxed}
                                selectedEntry={selectedEntry}
                                onEntryClicked={(newEntry)=>setSelectedEntry(newEntry)}
                                onErrorOccurred={handleComponentError}
            />
        </div>

        <div className={classes.detailsArea}>
            <EntryDetails entry={selectedEntry}
                          autoPlay={true}
                          showJobs={true}
                          loadJobs={false}
                          lightboxedCb={(entryId:string)=>setNewlyLightboxed((prevState) => prevState.concat(entryId))}
                          preLightboxInsert={selectedEntry ?
                              <LightboxDetailsInsert
                                  checkArchiveStatusClicked={()=>checkArchiveStatus(selectedEntry.id)}
                                  redoRestoreClicked={()=>{}}
                                  archiveEntryId={selectedEntry?.id}
                                  archiveEntryPath={selectedEntry?.path}
                                  lightboxEntry={lightboxDetails[selectedEntry.id]}
                                    user={selectedUser}
                              /> : null
                          }
            />
        </div>
    </div>
}

export default NewLightbox;