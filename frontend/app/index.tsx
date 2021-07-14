import React, {useEffect, useState} from 'react';
import {render} from 'react-dom';
import {BrowserRouter, Link, Redirect, Route, Switch} from 'react-router-dom';
import Raven from 'raven-js';
import axios from 'axios';

import ScanTargetEdit from './ScanTargets/ScanTargetEdit.jsx';
import ScanTargetsList from './ScanTargets/ScanTargetsList';
import NotFoundComponent from './NotFoundComponent.jsx';
import TopMenu from './TopMenu';
import AdminFront from './admin/AdminFront';
import AboutComponent from './admin/About';

import JobsList from './JobsList/JobsList';
import LoginStatusComponent from './Login/LoginStatusComponent';

import ProxyHealthDetail from './ProxyHealthDetail/ProxyHealthDetail.jsx';

import { library } from '@fortawesome/fontawesome-svg-core'
import { faStroopwafel, faCheckCircle, faCheck, faTimes, faTimesCircle, faRoad, faSearch,faThList,faWrench, faLightbulb, faFolderPlus, faFolderMinus, faFolder, faBookReader, faRedoAlt, faHome } from '@fortawesome/free-solid-svg-icons'
import { faChevronCircleDown,faChevronCircleRight,faTrashAlt, faFilm, faVolumeUp,faImage, faFile, faClock, faRunning, faExclamationTriangle, faHdd, faBalanceScale, faSyncAlt, faIndustry, faListOl} from '@fortawesome/free-solid-svg-icons'
import { faCompressArrowsAlt, faBug, faExclamation, faUnlink } from '@fortawesome/free-solid-svg-icons'
import UserList from "./Users/UserList.jsx";

import ProxyFrameworkList from "./ProxyFramework/ProxyFrameworkList";
import ProxyFrameworkAdd from './ProxyFramework/ProxyFrameworkAdd';

import Test419Component from "./testing/test419.jsx";
import {
    createMuiTheme,
    ThemeProvider
} from "@material-ui/core/styles";
import CssBaseline from "@material-ui/core/CssBaseline";

import {customisedTheme} from "./CustomisedTheme";
import {CircularProgress, Theme} from "@material-ui/core";
import NewBasicSearch from "./search/NewBasicSearch";
import NewBrowseComponent from "./browse/NewBrowseComponent";
import NewLightbox from "./Lightbox/NewLightbox";
import QuickRestoreComponent from "./admin/QuickRestore.jsx";
import PathCacheAdmin from "./admin/PathCacheAdmin.jsx";
import ItemView from "./ItemView/ItemView";
import DeletedItemsComponent from "./DeletedItems/DeletedItems";
import {UserContextProvider} from "./Context/UserContext";
import {GenericResponse, InvalidLoginResponse, UserDetails} from "./types";
import LoginComponent from "./LoginComponent";


library.add(faStroopwafel, faCheckCircle, faCheck, faTimes, faTimesCircle, faRoad,faSearch,faThList,faWrench, faLightbulb, faChevronCircleDown, faChevronCircleRight, faTrashAlt, faFolderPlus, faFolderMinus, faFolder);
library.add(faFilm, faVolumeUp, faImage, faFile, faClock, faRunning, faExclamationTriangle, faHdd, faBalanceScale, faSyncAlt, faBookReader, faBug, faCompressArrowsAlt, faIndustry, faRedoAlt, faHome, faListOl,);
library.add(faExclamation, faUnlink);

interface AppContainerState {
    userLogin?: UserDetails;
    lastError?: any;
    loading?:boolean;
}

const maxLoginAttempts = 5;

const App:React.FC = ()=> {
    const [userLogin, setUserLogin] = useState<UserDetails|undefined>(undefined);
    const [loading, setLoading] = useState(true);
    const [lastError, setLastError] = useState<string|undefined>(undefined);

    const theme = createMuiTheme(customisedTheme);

    const asyncSleep = (sleeptime:number) => {
        return new Promise<void>((resolve, reject)=>window.setTimeout(()=>resolve(), sleeptime));
    }

    const checkLoginRefresh = async (attempt:number):Promise<void> => {
        if(attempt>maxLoginAttempts) throw `Could not refresh login after ${attempt} attempts, giving up`;
        if(attempt>1) await asyncSleep(1000*(attempt-1));  //if we are in a retry loop don't spam the server
        try {
            const refreshResponse = await axios.post<GenericResponse>("/api/loginRefresh");
            if(refreshResponse.data.status=="not_needed") {
                console.log("token refresh not required");
                setLoading(false);
            } else {
                console.log(`refresh check successful on attempt ${attempt}: `, refreshResponse.data);
                return getLoginStatus(attempt + 1);
            }
        } catch(err) {
            console.error("could not refresh login: ")
        }
    }

    const getLoginStatus = async (attempt:number) => {
        try {
            const loginStatusResponse = await axios.get("/api/loginStatus", {validateStatus: (status) => status == 200 || status == 401})

            switch (loginStatusResponse.status) {
                case 200:
                    const userLoginData = loginStatusResponse.data as UserDetails;
                    setUserLogin(userLoginData);
                    setLoading(false);
                    break;
                case 401 || 403:   //we get this if the login is expired
                    const rejectionData = loginStatusResponse.data as InvalidLoginResponse;
                    if (rejectionData.status == "expired") {
                        return checkLoginRefresh(attempt);
                    } else {
                        setLastError(rejectionData.detail ?? rejectionData.toString());
                        setLoading(false);
                    }
                    break;
                default:
                    console.error("Unexpected response: ", loginStatusResponse.status, " ", loginStatusResponse.data);
                    setLastError(`Unexpected server response ${loginStatusResponse.status}`);
                    setLoading(false);
                    break;
            }
        } catch(err) {
            console.error(`Could not check login status: `, err);
            setLastError(err.toString());
            setLoading(false);
        }
    }

    useEffect(()=>{
        setLoading(true);
        axios.get("/system/publicdsn").then(response=> {
            Raven
                .config(response.data.publicDsn)
                .install();
            console.log("Sentry initialised for " + response.data.publicDsn);
        }).catch(error => {
            console.error("Could not intialise sentry", error);
        });

        getLoginStatus(1)
            .catch((err)=>{
                console.error("Could not refresh login: ", err);
            })

        const timerId = window.setInterval(()=>checkLoginRefresh(1), 60000) //check for token refresh once per minute
        return ()=>{
            window.clearInterval(timerId);
        }
    }, []);

    const userLoggedOut = () => {
        setUserLogin(undefined);
        asyncSleep(500).then(()=>window.location.reload());
    }

    return <ThemeProvider theme={theme}>
        <UserContextProvider value={{profile: userLogin, updateProfile: setUserLogin}}>
            <CssBaseline/>
            <TopMenu visible={true} isAdmin={true}/>
            <LoginStatusComponent userLoggedOutCb={userLoggedOut}/>
            {loading ? <CircularProgress/> :
                userLogin ?
                    <Switch>
                        <Route path="/test/419" component={Test419Component}/>
                        <Route path="/admin/pathcache" component={PathCacheAdmin}/>
                        <Route path="/admin/proxyHealth" component={ProxyHealthDetail}/>
                        <Route path="/admin/deleteditems" component={DeletedItemsComponent}/>
                        <Route path="/admin/proxyFramework/new" component={ProxyFrameworkAdd}/>
                        <Route path="/admin/proxyFramework" component={ProxyFrameworkList}/>
                        <Route path="/admin/about" component={AboutComponent}/>
                        <Route path="/admin/users" component={UserList}/>
                        <Route path="/admin/jobs/:jobid" component={JobsList}/>
                        <Route path="/admin/jobs" component={JobsList}/>
                        <Route path="/admin/scanTargets/:id" component={ScanTargetEdit}/>
                        <Route path="/admin/scanTargets" component={ScanTargetsList}/>
                        <Route path="/admin/quickrestore" component={QuickRestoreComponent}/>
                        <Route path="/admin" exact={true} component={AdminFront}/>
                        <Route path="/lightbox" exact={true} component={NewLightbox}/>
                        <Route path="/browse" exact={true} component={NewBrowseComponent}/>
                        <Route path="/item/:id" exact={true} component={ItemView}/>
                        <Route path="/search" exact={true} component={NewBasicSearch}/>
                        <Route path="/" exact={true} render={() => <Redirect to="/search"/>}/>
                        <Route default component={NotFoundComponent}/>
                    </Switch> : <LoginComponent/>
            }
        </UserContextProvider>
    </ThemeProvider>
}

render(<BrowserRouter basename="/"><App/></BrowserRouter>, document.getElementById('app'));
