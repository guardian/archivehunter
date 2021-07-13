import React from 'react';
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
import {handle419, setupInterceptor} from "./common/Handle419.jsx";

import Test419Component from "./testing/test419.jsx";
import {
    createMuiTheme,
    ThemeProvider
} from "@material-ui/core/styles";
import CssBaseline from "@material-ui/core/CssBaseline";

import {customisedTheme} from "./CustomisedTheme";
import {Theme} from "@material-ui/core";
import NewBasicSearch from "./search/NewBasicSearch";
import NewBrowseComponent from "./browse/NewBrowseComponent";
import NewLightbox from "./Lightbox/NewLightbox";
import QuickRestoreComponent from "./admin/QuickRestore.jsx";
import PathCacheAdmin from "./admin/PathCacheAdmin.jsx";
import ItemView from "./ItemView/ItemView";
import DeletedItemsComponent from "./DeletedItems/DeletedItems";
import RootComponent from "./RootComponent";
import {UserContextProvider} from "./Context/UserContext";
import {JwtDataShape} from "./Context/DecodedProfile";


library.add(faStroopwafel, faCheckCircle, faCheck, faTimes, faTimesCircle, faRoad,faSearch,faThList,faWrench, faLightbulb, faChevronCircleDown, faChevronCircleRight, faTrashAlt, faFolderPlus, faFolderMinus, faFolder);
library.add(faFilm, faVolumeUp, faImage, faFile, faClock, faRunning, faExclamationTriangle, faHdd, faBalanceScale, faSyncAlt, faBookReader, faBug, faCompressArrowsAlt, faIndustry, faRedoAlt, faHome, faListOl,);
library.add(faExclamation, faUnlink);

/**
 * set up an Axios Interceptor to handle automatic refreshing of credentials when a 419 (credentials expired)
 * is received.  This is done in the Handle419 module, seperated into named functions for ease of testing
 */
setupInterceptor();

interface AppContainerState {
    userLogin?: JwtDataShape;
    lastError?: any;
    loading?:boolean;
}

class App extends React.Component<any, AppContainerState> {
    theme: Theme;

    constructor(props:any){
        super(props);

        this.state = {
            userLogin: undefined,
            lastError: undefined,
            loading: false
        };

        axios.get("/system/publicdsn").then(response=> {
            Raven
                .config(response.data.publicDsn)
                .install();
            console.log("Sentry initialised for " + response.data.publicDsn);
        }).catch(error => {
            console.error("Could not intialise sentry", error);
        });
        this.userLoggedOut = this.userLoggedOut.bind(this);
        this.updateProfile = this.updateProfile.bind(this);
        this.theme = createMuiTheme(customisedTheme);
    }

    updateProfile(newValue: JwtDataShape|undefined, maybeCb?:()=>void) {
        this.setState({userLogin: newValue}, maybeCb);
    }

    componentDidMount(){
        this.setState({loading: true}, ()=>axios.get("/api/loginStatus")
            .then(response=> {
                this.setState({userLogin: response.data})
            }).catch(err=>{
                console.error(err);
                this.setState({lastError: err})
            }));
    }

    userLoggedOut(){
        this.updateProfile(undefined, ()=>window.location.reload());
    }

    render(){
        return <ThemeProvider theme={this.theme}>
            <UserContextProvider value={{profile: this.state.userLogin, updateProfile: this.updateProfile}}>
                <CssBaseline/>
                <TopMenu visible={true} isAdmin={true}/>
                <LoginStatusComponent userData={this.state.userLogin} userLoggedOutCb={this.userLoggedOut}/>
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
                    <Route path="/admin/scanTargets/:id" component={ScanTargetEdit}/> /*this also handles "new" */
                    <Route path="/admin/scanTargets" component={ScanTargetsList}/>
                    <Route path="/admin/quickrestore" component={QuickRestoreComponent}/>
                    <Route path="/admin" exact={true} component={AdminFront}/>
                    <Route path="/lightbox" exact={true} component={NewLightbox}/>
                    <Route path="/browse" exact={true} component={NewBrowseComponent}/>
                    <Route path="/item/:id" exact={true} component={ItemView}/>
                    <Route path="/search" exact={true} component={NewBasicSearch}/>
                    <Route path="/" exact={true} component={RootComponent}/>
                    <Route default component={NotFoundComponent}/>
                </Switch>
            </UserContextProvider>
        </ThemeProvider>
    }
}

render(<BrowserRouter basename="/"><App/></BrowserRouter>, document.getElementById('app'));
