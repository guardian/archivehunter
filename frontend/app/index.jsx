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
import BasicSearchComponent from './search/BasicSearchComponent.jsx';

import { library } from '@fortawesome/fontawesome-svg-core'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faStroopwafel, faCheckCircle, faTimesCircle, faRoad, faSearch,faThList,faWrench, faLightbulb } from '@fortawesome/free-solid-svg-icons'
library.add(faStroopwafel, faCheckCircle, faTimesCircle, faRoad,faSearch,faThList,faWrench, faLightbulb);

window.React = require('react');

class App extends React.Component {
    constructor(props){
        super(props);
        axios.get("/system/publicdsn").then(response=> {
            Raven
                .config(response.data.publicDsn)
                .install();
            console.log("Sentry initialised for " + response.data.publicDsn);
        }).catch(error => {
            console.error("Could not intialise sentry", error);
        });

    }

    render(){
        return <div>
            <TopMenu visible={true} isAdmin={true}/>
            <Switch>
                <Route path="/admin/scanTargets" component={ScanTargetsList}/>
                <Route path="/admin/scanTargets/:id" component={ScanTargetEdit}/> /*this also handles "new" */
                <Route path="/admin" exact={true} component={AdminFront}/>
                <Route path="/search" exact={true} component={BasicSearchComponent}/>
                <Route path="/" exact={true} component={FrontPage}/>
                <Route default component={NotFoundComponent}/>
            </Switch>
        </div>
    }
}

render(<BrowserRouter root="/"><App/></BrowserRouter>, document.getElementById('app'));
