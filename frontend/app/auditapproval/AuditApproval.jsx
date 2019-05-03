import React from 'react';
import axios from 'axios';
import RestoreStatsChart from './RestoreStatsChart.jsx';

class AuditApproval extends React.Component {
    constructor(props){
        super(props);

        this.state = {

        }
    }

    render(){
        return <div>
            <RestoreStatsChart/>
        </div>
    }
}

export default AuditApproval;