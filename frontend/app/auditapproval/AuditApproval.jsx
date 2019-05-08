import React from 'react';
import axios from 'axios';
import RestoreStatsChart from './RestoreStatsChart.jsx';
import MonthlyOverviewTable from "./MonthlyOverviewTable.jsx";
import ApprovalsList from "./ApprovalsList.jsx";

class AuditApproval extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            mainGraphType: "Restore",
            mainGraphCat: "user"
        }
    }

    render(){
        return <div>
            <div id="top-row" style={{overflow:"hidden", width: "100vw", height: "1200"}}>
            <RestoreStatsChart graphValues={this.state.mainGraphType} graphCategory={this.state.mainGraphCat}/>

                <select value={this.state.mainGraphType} onChange={evt=>this.setState({mainGraphType: evt.target.value})}>
                    <option value="Restore">Show Restores</option>
                    <option value="Download">Show Downloads</option>
                </select>
                <br/>
                <select value={this.state.mainGraphCat} onChange={evt=>this.setState({mainGraphCat: evt.target.value})}>
                    <option value="user">Group by users</option>
                    <option value="region">Group by regions</option>
                    <option value="collection">Group by archive collection</option>
                </select>
            </div>
            <div id="bottom-row" style={{overflow:"hidden", width: "100vw", height: "1200"}}>
                <MonthlyOverviewTable/>
                <ApprovalsList/>
            </div>

        </div>
    }
}

export default AuditApproval;