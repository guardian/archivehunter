import React from 'react';
import {render} from 'react-dom';
import {BrowserRouter, Link, Route, Switch} from 'react-router-dom';
import Raven from 'raven-js';
import axios from 'axios';

import ScanTargetEdit from './ScanTargets/ScanTargetEdit.jsx';
import ScanTargetsList from './ScanTargets/ScanTargetsList.jsx';
import NotFoundComponent from './NotFoundComponent.jsx';
import FrontPage from './FrontPage.jsx';
import TopMenu from './TopMenu.jsx';
import AdminFront from './admin/AdminFront.jsx';
import AboutComponent from './admin/About.jsx';

import BasicSearchComponent from './search/BasicSearchComponent.jsx';
import JobsList from './JobsList/JobsList.jsx';
import BrowseComponent from './browse/BrowseComponent.jsx';
import LoginStatusComponent from './Login/LoginStatusComponent.jsx';
import MyLightbox from './Lightbox/MyLightbox.jsx';

import ProxyHealthDetail from './ProxyHealthDetail/ProxyHealthDetail.jsx';

import { library } from '@fortawesome/fontawesome-svg-core'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faStroopwafel, faCheckCircle, faCheck, faTimes, faTimesCircle, faRoad, faSearch,faThList,faWrench, faLightbulb, faFolderPlus, faFolderMinus, faFolder, faBookReader, faRedoAlt, faHome } from '@fortawesome/free-solid-svg-icons'
import { faChevronCircleDown,faChevronCircleRight,faTrashAlt, faFilm, faVolumeUp,faImage, faFile, faClock, faRunning, faExclamationTriangle, faHdd, faBalanceScale, faSyncAlt, faIndustry, faListOl} from '@fortawesome/free-solid-svg-icons'
import { faCompressArrowsAlt, faBug, faExclamation, faUnlink } from '@fortawesome/free-solid-svg-icons'
import UserList from "./Users/UserList.jsx";

import ProxyFrameworkList from "./ProxyFramework/ProxyFrameworkList.jsx";
import ProxyFrameworkAdd from './ProxyFramework/ProxyFrameworkAdd.jsx';
import {handle419, setupInterceptor} from "./common/Handle419.jsx";

import ProxyHealthDash from "./ProxyHealth/ProxyHealthDash.jsx";
import QuickRestoreComponent from "./admin/QuickRestore.jsx";
import Test419Component from "./testing/test419.jsx";
import PathCacheAdmin from "./admin/PathCacheAdmin.jsx";

library.add(faStroopwafel, faCheckCircle, faCheck, faTimes, faTimesCircle, faRoad,faSearch,faThList,faWrench, faLightbulb, faChevronCircleDown, faChevronCircleRight, faTrashAlt, faFolderPlus, faFolderMinus, faFolder);
library.add(faFilm, faVolumeUp, faImage, faFile, faClock, faRunning, faExclamationTriangle, faHdd, faBalanceScale, faSyncAlt, faBookReader, faBug, faCompressArrowsAlt, faIndustry, faRedoAlt, faHome, faListOl,);
library.add(faExclamation, faUnlink);
window.React = require('react');

/**
 * set up an Axios Interceptor to handle automatic refreshing of credentials when a 419 (credentials expired)
 * is received.  This is done in the Handle419 module, seperated into named functions for ease of testing
 */
setupInterceptor();

class App extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            userLogin: null,
            lastError: null,
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
    }

    componentWillMount(){
        this.setState({loading: true}, ()=>axios.get("/api/loginStatus")
            .then(response=> {
                this.setState({userLogin: response.data})
            }).catch(err=>{
                console.error(err);
                this.setState({lastError: err})
            }));
    }

    userLoggedOut(){
        this.setState({userLogin: null}, ()=>location.reload(true));
    }

    render(){
        return <div>
            <TopMenu visible={true} isAdmin={this.state.userLogin ? this.state.userLogin.isAdmin : false}/>
            <LoginStatusComponent userData={this.state.userLogin} userLoggedOutCb={this.userLoggedOut}/>
            <Switch>
                <Route path="/test/419" component={Test419Component}/>
                <Route path="/admin/pathcache" component={PathCacheAdmin}/>
                <Route path="/admin/proxyHealth" component={ProxyHealthDetail}/>
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
                <Route path="/lightbox" exact={true} component={MyLightbox}/>
                <Route path="/browse" exact={true} component={BrowseComponent}/>
                <Route path="/search" exact={true} component={BasicSearchComponent}/>
                <Route path="/" exact={true} component={FrontPage}/>
                <Route default component={NotFoundComponent}/>
            </Switch>
        </div>
    }
}

render(<BrowserRouter root="/"><App/></BrowserRouter>, document.getElementById('app'));
