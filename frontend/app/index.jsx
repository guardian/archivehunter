import React from 'react';
import {render} from 'react-dom';
import {BrowserRouter, Link, Route, Switch} from 'react-router-dom';
import Raven from 'raven-js';
import axios from 'axios';

import ScanTargetEdit from './ScanTargets/ScanTargetEdit.jsx';
import ScanTargetsList from './ScanTargets/ScanTargetsList.jsx';
import NotFoundComponent from './NotFoundComponent.jsx';

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
        return <Switch>
            <Route path="/scanTargets" component={ScanTargetsList}/>
            <Route path="/scanTargets/:id" component={ScanTargetEdit}/> /*this also handles "new" */
            <Route default component={NotFoundComponent}/>
        </Switch>
    }
}

render(<BrowserRouter root="/"><App/></BrowserRouter>, document.getElementById('app'));
