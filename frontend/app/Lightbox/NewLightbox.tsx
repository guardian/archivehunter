import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import {makeStyles, Typography} from "@material-ui/core";
import {
    ArchiveEntry,
    ArchiveEntryResponse,
    LightboxBulk,
    LightboxBulkResponse,
    LightboxDetailsResponse, ObjectListResponse,
    UserDetails,
    UserDetailsResponse
} from "../types";
import UserSelector from "../common/UserSelector";
import BulkSelectionsScroll from "./BulkSelectionsScroll";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";

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
        gridColumnEnd: -1,
        gridRowStart: "title-area",
        gridRowEnd: "info-area"
    }
});

const NewLightbox:React.FC<RouteComponentProps> = (props) => {
    const classes = useStyles();
    const [userDetails, setUserDetails] = useState<UserDetails|undefined>(undefined);
    const [selectedUser, setSelectedUser] = useState<string>("my");
    const [bulkSelections, setBulkSelections] = useState<LightboxBulk[]>([]);
    const [selectedBulk, setSelectedBulk] = useState<string|undefined>(undefined);
    const [expiryDays, setExpiryDays] = useState<number>(10);
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [showingAlert, setShowingAlert] = useState(false);
    const [bulkSelectionsCount, setBulkSelectionsCount] = useState(0);
    const [loading, setLoading] = useState(false);

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
        //const detailsRequest = axios.get<LightboxDetailsResponse>("/api/lightbox/" + selectedUser+"/details");
        //const summaryRequest = axios.get<ObjectListResponse<ArchiveEntry>>(`/api/search/myLightBox?user=${selectedUser}&size=${pageSize}`);
        const loginDetailsRequest = axios.get<UserDetailsResponse>("/api/loginStatus");
        const bulkSelectionsRequest = axios.get<LightboxBulkResponse>("/api/lightbox/" + selectedUser+"/bulks");
        const configRequest = axios.get<ObjectListResponse<string>>("/api/config");
        return Promise.all([loginDetailsRequest, bulkSelectionsRequest, configRequest]);
    }

    const refreshData = async () => {
        setLoading(true);
        try {
            const results = await performLoad();

            //const detailsResult = results[0].data as LightboxDetailsResponse;
            //const summaryResult = results[1].data as ObjectListResponse<ArchiveEntry>;
            const loginDetailsResult = results[0].data as UserDetailsResponse;
            const bulkSelectionsResult = results[1].data as LightboxBulkResponse;
            const configResult = results[2].data as ObjectListResponse<string>;
            // setSearchResults(summaryResult.entries.map(entry=>
            //     Object.assign({}, entry, {details: detailsResult.entries.includes(entry.id) ? detailsResult.entries[entry.id] : null}))
            // );
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

    //reload the search if the currently selected bulk changes
    useEffect(()=>{
        refreshData();
    }, [selectedBulk]);

    useEffect(()=>{
        refreshData();
    }, []);

    return <div className={classes.browserWindow}>
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
                                  expiryDays={expiryDays}/>
        </div>
    </div>
}

export default NewLightbox;